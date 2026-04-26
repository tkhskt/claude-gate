import androidx.compose.ui.window.application
import com.tkhskt.claude.gate.desktop.DesktopApp
import com.tkhskt.claude.gate.desktop.awt.installTrayNpeSuppression

fun main() {
    installTrayNpeSuppression()
    application {
        DesktopApp()
    }
}
