package com.tkhskt.claude.gate.desktop.awt

import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit

/**
 * Installs an [EventQueue] wrapper that silently absorbs a known JDK bug:
 * when `TrayIcon` dispatches a MouseEvent, AWT's `LightweightDispatcher`
 * (auto-registered as an internal `AWTEventListener` for the MOUSE event mask)
 * tries to treat the event source as a `Component` — but `TrayIcon` is a
 * `java.lang.Object` subclass, not a Component, so the dispatcher's internal
 * `srcComponent` resolves to `null` and `isShowing()` throws NPE.
 *
 * The NPE is benign (our own MouseListener still receives the click on a
 * separate code path) but it spams stderr. We catch that exact NPE here and
 * drop it; anything else re-throws.
 */
fun installTrayNpeSuppression() {
    val system = Toolkit.getDefaultToolkit().systemEventQueue
    system.push(
        object : EventQueue() {
            override fun dispatchEvent(event: AWTEvent) {
                try {
                    super.dispatchEvent(event)
                } catch (e: NullPointerException) {
                    if (!isTrayLightweightDispatcherNpe(e)) throw e
                }
            }
        },
    )
}

private fun isTrayLightweightDispatcherNpe(e: NullPointerException): Boolean =
    e.stackTrace.any {
        it.className == "java.awt.LightweightDispatcher" &&
            it.methodName == "eventDispatched"
    }
