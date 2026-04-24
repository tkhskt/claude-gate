package com.tkhskt.claude.notification.desktop.mac

import co.touchlab.kermit.Logger
import com.sun.jna.Callback
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

    private val libObjc by lazy {
        runCatching { NativeLibrary.getInstance("objc.A") }.getOrNull()
    }
    private val libSystem by lazy {
        runCatching { NativeLibrary.getInstance("System") }.getOrNull()
    }
    private val selRegisterName by lazy { libObjc?.getFunction("sel_registerName") }
    private val objcGetClass by lazy { libObjc?.getFunction("objc_getClass") }
    private val objcMsgSend by lazy { libObjc?.getFunction("objc_msgSend") }
    private val dispatchAsyncF by lazy { libSystem?.getFunction("dispatch_async_f") }
    private val dispatchMainQueue by lazy {
        // `_dispatch_main_q` is the singleton main-queue global; its address is
        // what `dispatch_get_main_queue()` returns.
        runCatching { libSystem?.getGlobalVariableAddress("_dispatch_main_q") }.getOrNull()
    }

    private fun sel(name: String): Pointer? = selRegisterName?.invokePointer(arrayOf(name))
    private fun cls(name: String): Pointer? = objcGetClass?.invokePointer(arrayOf(name))

    fun interface DispatchWork : Callback {
        fun invoke(context: Pointer?)
    }

    // Keep strong refs so JNA callbacks aren't GC'd before AppKit invokes them.
    private val pendingWork = ConcurrentLinkedQueue<DispatchWork>()

    private fun runOnAppKitMain(block: () -> Unit) {
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
