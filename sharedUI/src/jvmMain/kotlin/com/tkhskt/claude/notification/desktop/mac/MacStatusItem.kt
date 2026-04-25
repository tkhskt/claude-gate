package com.tkhskt.claude.notification.desktop.mac

import co.touchlab.kermit.Logger
import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.awt.Color
import java.awt.MouseInfo
import java.awt.Point
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Native macOS NSStatusItem driven via JNA. Bypasses AWT's TrayIcon so the
 * background color we set on the button's CALayer fills AppKit's full
 * reserved slot — i.e. the entire click-highlight rectangle — instead of
 * just the image rect.
 *
 * Click handling: a runtime NSObject subclass is registered once, its
 * instance is wired as `statusItem.button.target` with action `onClick:`.
 * Click events are dispatched on the AppKit main thread; the registered
 * Kotlin lambda runs there.
 */
object MacStatusItem {
    private val log = Logger.withTag("MacStatusItem")
    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    private const val TARGET_CLASS_NAME = "CMNStatusItemClickTarget"
    private const val NS_VARIABLE_STATUS_ITEM_LENGTH = -1.0
    private const val NS_IMAGE_ONLY: Long = 1
    private const val LAYER_CORNER_RADIUS = 4.0
    // Fixed slot width (points). NSStatusBarButton adds intrinsic padding
    // around the image when length is variable, which keeps the slot wider
    // than we want. Pinning length here forces the slot to this exact width.
    private const val STATUS_ITEM_LENGTH = 22.0

    @Volatile private var statusItem: Pointer? = null
    @Volatile private var targetInstance: Pointer? = null

    // Strong refs — JNA callbacks must not be GC'd while the obj-c runtime
    // holds the IMP / target.
    @Suppress("unused")
    private var clickCallback: ClickImp? = null
    @Volatile private var onClickHandler: (() -> Unit)? = null

    fun interface ClickImp : Callback {
        fun invoke(self: Pointer?, sel: Pointer?, sender: Pointer?)
    }

    fun isInstalled(): Boolean = statusItem != null

    /**
     * Installs the status item asynchronously on the AppKit main thread.
     * Returns true on macOS (best-effort optimistic — actual creation runs
     * after this call returns; subsequent setIconAndBackground calls are
     * also dispatched onto the same queue, so they observe the install).
     */
    fun install(onClick: () -> Unit): Boolean {
        if (!isMac) return false
        if (statusItem != null) return true
        onClickHandler = onClick
        MacApp.runOnAppKitMain {
            try {
                installSync()
            } catch (t: Throwable) {
                log.w(t) { "installSync failed" }
            }
        }
        return true
    }

    fun uninstall() {
        if (!isMac) return
        val si = statusItem ?: return
        statusItem = null
        MacApp.runOnAppKitMain {
            runCatching {
                val msg = MacApp.objcMsgSend ?: return@runCatching
                val nsStatusBarCls = MacApp.cls("NSStatusBar") ?: return@runCatching
                val systemStatusBarSel = MacApp.sel("systemStatusBar") ?: return@runCatching
                val removeStatusItemSel = MacApp.sel("removeStatusItem:") ?: return@runCatching
                val releaseSel = MacApp.sel("release") ?: return@runCatching
                val nsStatusBar = msg.invokePointer(arrayOf(nsStatusBarCls, systemStatusBarSel))
                msg.invokeVoid(arrayOf(nsStatusBar, removeStatusItemSel, si))
                msg.invokeVoid(arrayOf(si, releaseSel))
            }.onFailure { t -> log.w(t) { "uninstall failed" } }
        }
    }

    /**
     * Updates the button's image (transparent-background white glyph) and
     * the layer's background color (null = clear, no fill). Dispatched on
     * AppKit main; if the install hasn't completed yet, the dispatch queue
     * orders this after install so it runs once the status item exists.
     */
    fun setIconAndBackground(image: BufferedImage, bgColor: Color?) {
        if (!isMac) return
        val pngBytes = imageToPng(image) ?: return
        MacApp.runOnAppKitMain {
            try {
                applyIconAndBackground(pngBytes, bgColor)
            } catch (t: Throwable) {
                log.w(t) { "setIconAndBackground failed" }
            }
        }
    }

    // --- AppKit-thread implementations ------------------------------------------------

    private fun installSync() {
        val msg = MacApp.objcMsgSend ?: return
        val nsStatusBarCls = MacApp.cls("NSStatusBar") ?: return
        val systemStatusBarSel = MacApp.sel("systemStatusBar") ?: return
        val statusItemWithLengthSel = MacApp.sel("statusItemWithLength:") ?: return
        val retainSel = MacApp.sel("retain") ?: return
        val buttonSel = MacApp.sel("button") ?: return
        val setWantsLayerSel = MacApp.sel("setWantsLayer:") ?: return
        val setImagePositionSel = MacApp.sel("setImagePosition:") ?: return
        val setTargetSel = MacApp.sel("setTarget:") ?: return
        val setActionSel = MacApp.sel("setAction:") ?: return
        val onClickSel = MacApp.sel("onClick:") ?: return

        val nsStatusBar = msg.invokePointer(arrayOf(nsStatusBarCls, systemStatusBarSel))
        if (nsStatusBar == null || nsStatusBar == Pointer.NULL) {
            log.w { "systemStatusBar returned null" }
            return
        }
        val item = msg.invokePointer(
            arrayOf(nsStatusBar, statusItemWithLengthSel, NS_VARIABLE_STATUS_ITEM_LENGTH),
        ) ?: return
        if (item == Pointer.NULL) return
        // statusItemWithLength: returns autoreleased — retain so the item
        // outlives the autorelease pool drain.
        msg.invokePointer(arrayOf(item, retainSel))

        // Pin slot width to a fixed length, bypassing the intrinsic padding
        // that NSStatusBarButton adds when length is variable.
        val setLengthSel = MacApp.sel("setLength:")
        if (setLengthSel != null) {
            msg.invokeVoid(arrayOf(item, setLengthSel, STATUS_ITEM_LENGTH))
        }

        val button = msg.invokePointer(arrayOf(item, buttonSel))
        if (button != null && button != Pointer.NULL) {
            msg.invokeVoid(arrayOf(button, setWantsLayerSel, 1L))
            msg.invokeVoid(arrayOf(button, setImagePositionSel, NS_IMAGE_ONLY))
            // Also turn on layer-backing for the button's window contentView
            // so we can paint the slot's full extent there as a fallback in
            // case button.bounds < window.contentView.bounds (cell padding).
            val windowSel = MacApp.sel("window")
            val contentViewSel = MacApp.sel("contentView")
            if (windowSel != null && contentViewSel != null) {
                val window = msg.invokePointer(arrayOf(button, windowSel))
                if (window != null && window != Pointer.NULL) {
                    val cv = msg.invokePointer(arrayOf(window, contentViewSel))
                    if (cv != null && cv != Pointer.NULL) {
                        msg.invokeVoid(arrayOf(cv, setWantsLayerSel, 1L))
                    }
                }
            }
            val target = ensureTargetInstance()
            if (target != null) {
                msg.invokeVoid(arrayOf(button, setTargetSel, target))
                msg.invokeVoid(arrayOf(button, setActionSel, onClickSel))
            }
        }

        statusItem = item
        log.i { "NSStatusItem installed" }
    }

    private fun applyIconAndBackground(pngBytes: ByteArray, bgColor: Color?) {
        val si = statusItem ?: return
        val msg = MacApp.objcMsgSend ?: return
        val buttonSel = MacApp.sel("button") ?: return
        val setImageSel = MacApp.sel("setImage:") ?: return
        val layerSel = MacApp.sel("layer") ?: return
        val setBackgroundColorSel = MacApp.sel("setBackgroundColor:") ?: return
        val setCornerRadiusSel = MacApp.sel("setCornerRadius:") ?: return
        val setMasksToBoundsSel = MacApp.sel("setMasksToBounds:") ?: return
        val releaseSel = MacApp.sel("release") ?: return

        val button = msg.invokePointer(arrayOf(si, buttonSel))
        if (button == null || button == Pointer.NULL) return

        val nsImage = nsImageFromPng(pngBytes)
        if (nsImage != null && nsImage != Pointer.NULL) {
            msg.invokeVoid(arrayOf(button, setImageSel, nsImage))
            // setImage: retains; balance our +1 from alloc/init to avoid leaks.
            msg.invokeVoid(arrayOf(nsImage, releaseSel))
        }

        val targetLayers = collectBackgroundLayers(button, msg, layerSel)
        if (targetLayers.isEmpty()) return

        val cgColor = bgColor?.let { nsCGColor(it) }
        for (layer in targetLayers) {
            if (bgColor == null || cgColor == null || cgColor == Pointer.NULL) {
                msg.invokeVoid(arrayOf(layer, setBackgroundColorSel, Pointer.NULL))
                msg.invokeVoid(arrayOf(layer, setCornerRadiusSel, 0.0))
                msg.invokeVoid(arrayOf(layer, setMasksToBoundsSel, 0L))
            } else {
                msg.invokeVoid(arrayOf(layer, setBackgroundColorSel, cgColor))
                msg.invokeVoid(arrayOf(layer, setCornerRadiusSel, LAYER_CORNER_RADIUS))
                msg.invokeVoid(arrayOf(layer, setMasksToBoundsSel, 1L))
            }
        }
    }

    // Returns the set of layers we want to paint the background onto: the
    // button's backing layer plus its window contentView's backing layer
    // (these are usually the same object, but if NSStatusBarButton is wrapped
    // in a contentView, painting both fills the slot's full bounds).
    private fun collectBackgroundLayers(
        button: Pointer,
        msg: com.sun.jna.Function,
        layerSel: Pointer,
    ): List<Pointer> {
        val layers = mutableListOf<Pointer>()
        val buttonLayer = msg.invokePointer(arrayOf(button, layerSel))
        if (buttonLayer != null && buttonLayer != Pointer.NULL) layers += buttonLayer
        val windowSel = MacApp.sel("window")
        val contentViewSel = MacApp.sel("contentView")
        if (windowSel != null && contentViewSel != null) {
            val window = msg.invokePointer(arrayOf(button, windowSel))
            if (window != null && window != Pointer.NULL) {
                val cv = msg.invokePointer(arrayOf(window, contentViewSel))
                if (cv != null && cv != Pointer.NULL) {
                    val cvLayer = msg.invokePointer(arrayOf(cv, layerSel))
                    if (cvLayer != null && cvLayer != Pointer.NULL && cvLayer != buttonLayer) {
                        layers += cvLayer
                    }
                }
            }
        }
        return layers
    }

    private fun ensureTargetInstance(): Pointer? {
        targetInstance?.let { return it }
        val msg = MacApp.objcMsgSend ?: return null
        val cls = ensureTargetClass() ?: return null
        val allocSel = MacApp.sel("alloc") ?: return null
        val initSel = MacApp.sel("init") ?: return null
        val allocated = msg.invokePointer(arrayOf(cls, allocSel)) ?: return null
        if (allocated == Pointer.NULL) return null
        val instance = msg.invokePointer(arrayOf(allocated, initSel)) ?: return null
        if (instance == Pointer.NULL) return null
        targetInstance = instance
        return instance
    }

    private fun ensureTargetClass(): Pointer? {
        val existing = MacApp.cls(TARGET_CLASS_NAME)
        if (existing != null && existing != Pointer.NULL) return existing
        val libObjc = MacApp.libObjc ?: return null
        val nsObject = MacApp.cls("NSObject") ?: return null
        val allocateClassPair = libObjc.getFunction("objc_allocateClassPair")
        val classAddMethod = libObjc.getFunction("class_addMethod")
        val registerClassPair = libObjc.getFunction("objc_registerClassPair")
        val onClickSel = MacApp.sel("onClick:") ?: return null

        val newCls = allocateClassPair.invokePointer(
            arrayOf(nsObject, TARGET_CLASS_NAME, 0L),
        ) ?: return null
        if (newCls == Pointer.NULL) return null

        val callback = ClickImp { _, _, _ ->
            try {
                onClickHandler?.invoke()
            } catch (t: Throwable) {
                log.w(t) { "onClick handler threw" }
            }
        }
        clickCallback = callback // keep alive for the process lifetime

        // "v@:@" — void (id self, SEL _cmd, id sender)
        classAddMethod.invokeInt(arrayOf(newCls, onClickSel, callback, "v@:@"))
        registerClassPair.invokeVoid(arrayOf(newCls))
        return newCls
    }

    private fun nsImageFromPng(bytes: ByteArray): Pointer? {
        val msg = MacApp.objcMsgSend ?: return null
        val nsDataCls = MacApp.cls("NSData") ?: return null
        val nsImageCls = MacApp.cls("NSImage") ?: return null
        val dataWithBytesLengthSel = MacApp.sel("dataWithBytes:length:") ?: return null
        val allocSel = MacApp.sel("alloc") ?: return null
        val initWithDataSel = MacApp.sel("initWithData:") ?: return null
        val setTemplateSel = MacApp.sel("setTemplate:") ?: return null

        val mem = Memory(bytes.size.toLong())
        mem.write(0, bytes, 0, bytes.size)
        val nsData = msg.invokePointer(
            arrayOf(nsDataCls, dataWithBytesLengthSel, mem, bytes.size.toLong()),
        ) ?: return null
        if (nsData == Pointer.NULL) return null
        val allocated = msg.invokePointer(arrayOf(nsImageCls, allocSel)) ?: return null
        if (allocated == Pointer.NULL) return null
        val image = msg.invokePointer(arrayOf(allocated, initWithDataSel, nsData)) ?: return null
        if (image == Pointer.NULL) return null
        // We provide our own colors; don't let the menu bar tint the image.
        msg.invokeVoid(arrayOf(image, setTemplateSel, 0L))
        return image
    }

    private fun nsCGColor(c: Color): Pointer? {
        val msg = MacApp.objcMsgSend ?: return null
        val nsColorCls = MacApp.cls("NSColor") ?: return null
        val sel = MacApp.sel("colorWithSRGBRed:green:blue:alpha:") ?: return null
        val cgColorSel = MacApp.sel("CGColor") ?: return null
        val nsColor = msg.invokePointer(
            arrayOf(
                nsColorCls,
                sel,
                c.red / 255.0,
                c.green / 255.0,
                c.blue / 255.0,
                c.alpha / 255.0,
            ),
        ) ?: return null
        if (nsColor == Pointer.NULL) return null
        return msg.invokePointer(arrayOf(nsColor, cgColorSel))
    }

    private fun imageToPng(image: BufferedImage): ByteArray? {
        return runCatching {
            ByteArrayOutputStream().use { out ->
                ImageIO.write(image, "png", out)
                out.toByteArray()
            }
        }.getOrElse { t ->
            log.w(t) { "imageToPng failed" }
            null
        }
    }

    /**
     * Convenience: current pointer location. Used by the click handler in
     * DesktopApp to anchor the popover under the menu-bar icon (we don't
     * have NSEvent geometry from a target/action callback, so the mouse
     * location at click time is the best-available proxy).
     */
    fun pointerLocation(): Point = runCatching {
        MouseInfo.getPointerInfo()?.location
    }.getOrNull() ?: Point(0, 0)
}
