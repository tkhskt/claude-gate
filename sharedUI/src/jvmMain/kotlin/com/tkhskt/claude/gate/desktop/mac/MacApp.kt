package com.tkhskt.claude.gate.desktop.mac

import co.touchlab.kermit.Logger
import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred

/**
 * Thin JNA bridge to AppKit for the pieces we need from Kotlin:
 * `[NSApp activateIgnoringOtherApps:YES]` (to bring the process to the
 * foreground on popover show) and `[NSApp isActive]` (to detect when focus
 * has left our app so the popover can close itself).
 *
 * All NSApp access is dispatched onto the AppKit main thread via
 * `dispatch_async_f` — AWT's EDT is NOT the same thread as `[NSThread mainThread]`
 * on JDK 17+/macOS, and calling AppKit APIs off-main raises
 * `NSInternalInconsistencyException` and aborts the process.
 */
object MacApp {
    private val log = Logger.withTag("MacApp")
    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    internal val libObjc by lazy {
        runCatching { NativeLibrary.getInstance("objc.A") }.getOrNull()
    }
    private val libSystem by lazy {
        runCatching { NativeLibrary.getInstance("System") }.getOrNull()
    }
    private val selRegisterName by lazy { libObjc?.getFunction("sel_registerName") }
    private val objcGetClass by lazy { libObjc?.getFunction("objc_getClass") }
    internal val objcMsgSend by lazy { libObjc?.getFunction("objc_msgSend") }
    private val dispatchAsyncF by lazy { libSystem?.getFunction("dispatch_async_f") }
    private val dispatchMainQueue by lazy {
        // `_dispatch_main_q` is the singleton main-queue global; its address is
        // what `dispatch_get_main_queue()` returns.
        runCatching { libSystem?.getGlobalVariableAddress("_dispatch_main_q") }.getOrNull()
    }

    internal fun sel(name: String): Pointer? = selRegisterName?.invokePointer(arrayOf(name))
    internal fun cls(name: String): Pointer? = objcGetClass?.invokePointer(arrayOf(name))

    fun interface DispatchWork : Callback {
        fun invoke(context: Pointer?)
    }

    // Keep strong refs so JNA callbacks aren't GC'd before AppKit invokes them.
    private val pendingWork = ConcurrentLinkedQueue<DispatchWork>()

    internal fun runOnAppKitMain(block: () -> Unit) {
        val queue = dispatchMainQueue
        val asyncF = dispatchAsyncF
        if (queue == null || asyncF == null) {
            // Fallback: run inline. May still crash on wrong thread, but we tried.
            block()
            return
        }
        val work = DispatchWork { _ ->
            try {
                block()
            } catch (t: Throwable) {
                log.w(t) { "runOnAppKitMain block threw" }
            }
        }
        pendingWork.add(work)
        try {
            asyncF.invokeVoid(arrayOf(queue, Pointer.NULL, work))
        } catch (t: Throwable) {
            pendingWork.remove(work)
            log.w(t) { "dispatch_async_f failed" }
        }
    }

    /** `[NSApp activateIgnoringOtherApps:YES]` — pull our app to the foreground. */
    fun activateApp() {
        if (!isMac) return
        val msg = objcMsgSend ?: return
        runOnAppKitMain {
            runCatching {
                val nsAppClass = cls("NSApplication") ?: return@runCatching
                val sharedApplication = sel("sharedApplication") ?: return@runCatching
                val activateSel = sel("activateIgnoringOtherApps:") ?: return@runCatching
                val nsApp = msg.invokePointer(arrayOf(nsAppClass, sharedApplication))
                msg.invokeVoid(arrayOf(nsApp, activateSel, 1L))
            }.onFailure { t -> log.w(t) { "activateApp failed" } }
        }
    }

    /**
     * Round the popover NSWindow's contentView layer to [radius] pt. Matches
     * the window by [title] in `[NSApp windows]`. Compose Desktop's
     * `transparent = true` clears the window background but doesn't clip the
     * contentView, so without this the AWT window stays visibly square even
     * when the inner Compose Surface is rounded.
     */
    fun roundPopoverWindow(title: String, radius: Double) {
        if (!isMac) return
        val msg = objcMsgSend ?: return
        runOnAppKitMain {
            runCatching {
                val nsAppClass = cls("NSApplication") ?: return@runCatching
                val nsStringClass = cls("NSString") ?: return@runCatching
                val sharedApplication = sel("sharedApplication") ?: return@runCatching
                val windowsSel = sel("windows") ?: return@runCatching
                val countSel = sel("count") ?: return@runCatching
                val objectAtIndexSel = sel("objectAtIndex:") ?: return@runCatching
                val titleSel = sel("title") ?: return@runCatching
                val isEqualToStringSel = sel("isEqualToString:") ?: return@runCatching
                val contentViewSel = sel("contentView") ?: return@runCatching
                val setWantsLayerSel = sel("setWantsLayer:") ?: return@runCatching
                val layerSel = sel("layer") ?: return@runCatching
                val setCornerRadiusSel = sel("setCornerRadius:") ?: return@runCatching
                val setMasksToBoundsSel = sel("setMasksToBounds:") ?: return@runCatching
                val setOpaqueSel = sel("setOpaque:") ?: return@runCatching
                val setBackgroundColorSel = sel("setBackgroundColor:") ?: return@runCatching
                val nsColorClass = cls("NSColor") ?: return@runCatching
                val clearColorSel = sel("clearColor") ?: return@runCatching
                val stringWithUTF8Sel = sel("stringWithUTF8String:") ?: return@runCatching

                val cBytes = title.toByteArray(Charsets.UTF_8) + 0
                val cMem = Memory(cBytes.size.toLong())
                cMem.write(0, cBytes, 0, cBytes.size)
                val nsTitle = msg.invokePointer(arrayOf(nsStringClass, stringWithUTF8Sel, cMem))
                val clearColor = msg.invokePointer(arrayOf(nsColorClass, clearColorSel))

                val nsApp = msg.invokePointer(arrayOf(nsAppClass, sharedApplication))
                val windowsArr = msg.invokePointer(arrayOf(nsApp, windowsSel))
                val count = msg.invokeLong(arrayOf(windowsArr, countSel))
                for (i in 0L until count) {
                    val w = msg.invokePointer(arrayOf(windowsArr, objectAtIndexSel, i))
                    val wTitle = msg.invokePointer(arrayOf(w, titleSel))
                    if (wTitle == null || wTitle == Pointer.NULL) continue
                    val isMatch = msg.invokeLong(arrayOf(wTitle, isEqualToStringSel, nsTitle)) and 0xFFL != 0L
                    if (!isMatch) continue
                    msg.invokeVoid(arrayOf(w, setOpaqueSel, 0L))
                    msg.invokeVoid(arrayOf(w, setBackgroundColorSel, clearColor))
                    val contentView = msg.invokePointer(arrayOf(w, contentViewSel))
                    msg.invokeVoid(arrayOf(contentView, setWantsLayerSel, 1L))
                    val layer = msg.invokePointer(arrayOf(contentView, layerSel))
                    msg.invokeVoid(arrayOf(layer, setCornerRadiusSel, radius))
                    msg.invokeVoid(arrayOf(layer, setMasksToBoundsSel, 1L))
                }
            }.onFailure { t -> log.w(t) { "roundPopoverWindow failed" } }
        }
    }

    /**
     * Returns `[NSApp isActive]` — true when this app is frontmost. Read on
     * the AppKit main thread via [runOnAppKitMain] + [CompletableDeferred].
     * Defaults to `true` on non-mac or if reading fails (don't spuriously close).
     */
    suspend fun isAppActive(): Boolean {
        if (!isMac) return true
        val msg = objcMsgSend ?: return true
        val deferred = CompletableDeferred<Boolean>()
        runOnAppKitMain {
            val result = runCatching {
                val nsAppClass = cls("NSApplication") ?: return@runCatching true
                val sharedApplication = sel("sharedApplication") ?: return@runCatching true
                val isActiveSel = sel("isActive") ?: return@runCatching true
                val nsApp = msg.invokePointer(arrayOf(nsAppClass, sharedApplication))
                val v = msg.invokeLong(arrayOf(nsApp, isActiveSel))
                (v and 0xFFL) != 0L
            }.getOrElse { true }
            deferred.complete(result)
        }
        return deferred.await()
    }
}
