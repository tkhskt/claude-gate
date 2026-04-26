package com.tkhskt.claude.notification.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.tkhskt.claude.notification.desktop.mac.MacApp
import com.tkhskt.claude.notification.desktop.mac.MacStatusItem
import com.tkhskt.claude.notification.permission.PermissionRequestHolder
import com.tkhskt.claude.notification.popover.PopoverContent
import com.tkhskt.claude.notification.server.PermissionServer
import com.tkhskt.claude.notification.theme.AppTheme
import kotlinx.coroutines.delay
import javax.swing.SwingUtilities
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage

private const val POPOVER_TITLE = "Claude Notification"
private const val POPOVER_WIDTH_DP = 360
private const val POPOVER_HEIGHT_DP = 420
private const val POPOVER_WIDE_WIDTH_DP = 680
private const val POPOVER_WIDE_HEIGHT_DP = 620
private const val MENU_BAR_OFFSET_DP = 28
private const val SCREEN_EDGE_MARGIN_DP = 8
private const val TRAY_ICON_WIDTH = 22
// Match menu-bar thickness so the image's intrinsic bounds fill the full
// slot vertically — NSStatusBarButton's layer auto-tracks bounds, and
// bounds tracks the image rect. Without this the colored layer would only
// fill the image's height, leaving vertical gaps inside the slot.
private const val TRAY_ICON_HEIGHT = 22
private const val TRAY_ICON_GLYPH = 14
private const val TRAY_ICON_CORNER_RADIUS = 4

private val FILE_EDIT_TOOLS = setOf("Edit", "Write")

private enum class TrayState { IDLE, AWAITING }

@Composable
fun ApplicationScope.DesktopApp() {
    val holder = remember { PermissionRequestHolder() }
    val server = remember { PermissionServer(holder) }
    // Remembered tray-click screen coordinates; re-used as the anchor for
    // auto-opens (permission-request triggered) so all opens land in the same
    // spot even without a fresh click.
    var trayAnchor by remember { mutableStateOf<Point?>(null) }

    var trayIconRef by remember { mutableStateOf<TrayIcon?>(null) }
    var nativeTrayInstalled by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        server.start()
        val installedNative = MacStatusItem.install {
            // Click runs on AppKit main; bounce to the JVM via these mutations
            // (StateFlow setters and primitive var assignments are thread-safe
            // in our usage).
            trayAnchor = MacStatusItem.pointerLocation()
            holder.togglePopover()
        }
        nativeTrayInstalled = installedNative
        val trayIcon = if (!installedNative) {
            installTrayIcon { clickAt ->
                trayAnchor = clickAt
                holder.togglePopover()
            }
        } else {
            null
        }
        trayIconRef = trayIcon
        onDispose {
            server.stop()
            if (installedNative) {
                MacStatusItem.uninstall()
            }
            trayIcon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
            trayIconRef = null
            nativeTrayInstalled = false
        }
    }

    // Reflect holder state in the menu bar icon:
    //   - native path: white glyph image + layer.backgroundColor toggled
    //     between transparent (idle) and amber (awaiting). Layer fills the
    //     entire reserved slot so the colored area matches the highlight.
    //   - AWT fallback: composite image with the rounded amber rect baked in.
    LaunchedEffect(nativeTrayInstalled, trayIconRef) {
        val nativeOn = nativeTrayInstalled
        val icon = trayIconRef
        if (!nativeOn && icon == null) return@LaunchedEffect
        holder.pending.collect { pending ->
            val state = if (pending.isNotEmpty()) TrayState.AWAITING else TrayState.IDLE
            if (nativeOn) {
                val glyph = createTrayIconImage(TrayState.IDLE) // glyph only, transparent
                val bg = if (state == TrayState.AWAITING) {
                    Color(0xFF, 0xC1, 0x07, 0xFF)
                } else {
                    null
                }
                MacStatusItem.setIconAndBackground(glyph, bg)
            } else {
                val image = createTrayIconImage(state)
                SwingUtilities.invokeLater { icon?.image = image }
            }
        }
    }

    val visibleTarget by holder.popoverVisible.collectAsState()
    val pending by holder.pending.collectAsState()
    // Wide layout if any pending request is a file edit/write — diff blocks
    // get visibly cramped in the narrow layout, so we widen as soon as one
    // appears. Tool-category flips drive auto-resize (see below).
    val isWide = pending.any { it.request.toolName in FILE_EDIT_TOOLS }

    val windowState = rememberWindowState(
        size = DpSize(POPOVER_WIDTH_DP.dp, POPOVER_HEIGHT_DP.dp),
        position = popoverPosition(POPOVER_WIDTH_DP, anchor = null),
    )

    // Auto-size only when the tool *category* flips. Within the same category
    // we leave windowState.size alone so the user's manual resize is kept.
    LaunchedEffect(isWide) {
        val width = if (isWide) POPOVER_WIDE_WIDTH_DP else POPOVER_WIDTH_DP
        val height = if (isWide) POPOVER_WIDE_HEIGHT_DP else POPOVER_HEIGHT_DP
        windowState.size = DpSize(width.dp, height.dp)
    }

    // Compute the target position *before* flipping `visible` so the AWT window
    // is ordered front at the correct coordinates on the first frame (no flash
    // at the stale position).
    var renderVisible by remember { mutableStateOf(false) }
    LaunchedEffect(visibleTarget) {
        if (visibleTarget) {
            windowState.position = popoverPosition(
                widthDp = windowState.size.width.value.toInt(),
                anchor = trayAnchor,
            )
            renderVisible = true
        } else {
            renderVisible = false
        }
    }

    Window(
        visible = renderVisible,
        onCloseRequest = { holder.setPopoverVisible(false) },
        state = windowState,
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        resizable = true,
        focusable = true,
        title = POPOVER_TITLE,
    ) {
        LaunchedEffect(renderVisible) {
            if (!renderVisible) return@LaunchedEffect
            // Pull the process to the foreground so NSApp.isActive becomes the
            // baseline we poll against. Borderless AWT windows can't become
            // key (canBecomeKeyWindow = NO), so WindowFocusListener isn't
            // reliable — we treat app-level activation as the source of truth.
            MacApp.activateApp()
            // Round the underlying NSWindow's contentView layer. Compose's
            // `transparent = true` doesn't clip the AWT contentView, so without
            // this the window edges stay visibly square. Re-applied on every
            // re-show because the contentView may be recreated.
            MacApp.roundPopoverWindow(POPOVER_TITLE, radius = 12.0)
            window.toFront()
            window.requestFocus()

            // Poll app-activation; close when the user has clicked into any
            // other app. Space-following support was removed because sustaining
            // activation across Space switches needs a richer event model.
            delay(250)
            while (true) {
                if (!MacApp.isAppActive()) {
                    holder.setPopoverVisible(false)
                    break
                }
                delay(150)
            }
        }
        AppTheme(onThemeChanged = {}) {
            PopoverContent(
                holder = holder,
                onQuit = { kotlin.system.exitProcess(0) },
            )
        }
    }
}

private fun installTrayIcon(onClick: (Point) -> Unit): TrayIcon? {
    if (!SystemTray.isSupported()) return null
    val tray = runCatching { SystemTray.getSystemTray() }.getOrNull() ?: return null
    val icon = TrayIcon(createTrayIconImage(TrayState.IDLE), "Claude Notification").apply {
        // keep the natural wider aspect ratio so the menu-bar slot grows to
        // match and the background color can occupy visible width.
        isImageAutoSize = false
        addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    onClick(e.locationOnScreen)
                }
            },
        )
    }
    return runCatching {
        tray.add(icon)
        icon
    }.getOrNull()
}

private fun createTrayIconImage(state: TrayState): BufferedImage {
    val background = when (state) {
        TrayState.IDLE -> null                              // transparent
        TrayState.AWAITING -> Color(0xFF, 0xC1, 0x07, 0xFF) // amber 500
    }
    val foreground = Color(255, 255, 255, 255)
    val image = BufferedImage(TRAY_ICON_WIDTH, TRAY_ICON_HEIGHT, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        if (background != null) {
            g.color = background
            g.fillRoundRect(
                0,
                0,
                TRAY_ICON_WIDTH,
                TRAY_ICON_HEIGHT,
                TRAY_ICON_CORNER_RADIUS * 2,
                TRAY_ICON_CORNER_RADIUS * 2,
            )
        }
        g.color = foreground
        g.stroke = BasicStroke(2f)
        val glyphX = (TRAY_ICON_WIDTH - TRAY_ICON_GLYPH) / 2
        val glyphY = (TRAY_ICON_HEIGHT - TRAY_ICON_GLYPH) / 2
        g.drawOval(glyphX, glyphY, TRAY_ICON_GLYPH, TRAY_ICON_GLYPH)
        g.fillOval(TRAY_ICON_WIDTH / 2 - 2, TRAY_ICON_HEIGHT / 2 - 2, 4, 4)
    } finally {
        g.dispose()
    }
    return image
}

private fun popoverPosition(widthDp: Int, anchor: Point?): WindowPosition {
    val screenBounds = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.bounds
    }.getOrNull()
    val screenX = screenBounds?.x ?: 0
    val screenY = screenBounds?.y ?: 0
    val screenWidth = screenBounds?.width ?: 1440
    // Anchor priority: explicit click coords → cached NSStatusItem center →
    // top-right fallback.  The status-item route makes auto-opens land under
    // the menu-bar icon even when the user never clicked the tray.
    val centerX = anchor?.x
        ?: MacStatusItem.statusItemScreenCenterX()?.toInt()
    val x = if (centerX != null) {
        (centerX - widthDp / 2).coerceIn(
            screenX + SCREEN_EDGE_MARGIN_DP,
            screenX + screenWidth - widthDp - SCREEN_EDGE_MARGIN_DP,
        )
    } else {
        screenX + screenWidth - widthDp - SCREEN_EDGE_MARGIN_DP
    }
    val y = screenY + MENU_BAR_OFFSET_DP
    return WindowPosition(x.dp, y.dp)
}
