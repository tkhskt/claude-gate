import androidx.compose.ui.window.application
import com.tkhskt.claude.gate.desktop.DesktopApp
import com.tkhskt.claude.gate.desktop.awt.installTrayNpeSuppression
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream

fun main() {
    redirectLogsToFile()
    installTrayNpeSuppression()
    application {
        DesktopApp()
    }
}

/**
 * When launched from Finder/Launchpad, stdout/stderr are not connected to
 * any terminal — Kermit's default println-based writer effectively drops
 * everything. Redirect both to `~/Library/Logs/claude-gate.log` so
 * users (and we) can diagnose Finder-launch issues by tailing that file.
 *
 * Failures here are non-fatal — silently fall back to the JVM defaults
 * (terminal launches still work the same way).
 */
private fun redirectLogsToFile() {
    runCatching {
        val home = System.getProperty("user.home") ?: return
        val logDir = File(home, "Library/Logs")
        if (!logDir.exists() && !logDir.mkdirs()) return
        val logFile = File(logDir, "claude-gate.log")
        val stream = PrintStream(FileOutputStream(logFile, true), true, Charsets.UTF_8)
        System.setOut(stream)
        System.setErr(stream)
    }
}
