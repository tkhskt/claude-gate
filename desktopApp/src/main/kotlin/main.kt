import androidx.compose.ui.window.application
import com.tkhskt.claude.notification.desktop.DesktopApp
import com.tkhskt.claude.notification.desktop.awt.installTrayNpeSuppression

fun main() {
    installTrayNpeSuppression()
    application {
        DesktopApp()
    }
}
