package com.goldenpaw

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.goldenpaw.di.allModules
import com.goldenpaw.platform.DesktopNotifier
import com.goldenpaw.ui.LaunchRequest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import com.goldenpaw.ui.designsystem.drawPaw
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.startKoin

fun main() {
    val koin = startKoin { modules(allModules()) }.koin
    val notifier = koin.get<DesktopNotifier>()
    val launchRequests = MutableStateFlow<LaunchRequest?>(null)

    application {
        val trayState = rememberTrayState()
        notifier.send = { title, body -> trayState.sendNotification(Notification(title, body, Notification.Type.Info)) }
        val icon = PawPainter
        Tray(
            state = trayState,
            icon = icon,
            tooltip = "GoldenPaw",
            menu = {
                Item("Quick log", onClick = { launchRequests.value = LaunchRequest(LaunchRequest.OPEN_QUICK_LOG, null, System.nanoTime()) })
                Item("Daily check-in", onClick = { launchRequests.value = LaunchRequest(LaunchRequest.OPEN_CHECKIN, null, System.nanoTime()) })
                Separator()
                Item("Quit GoldenPaw", onClick = ::exitApplication)
            },
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = "GoldenPaw",
            icon = icon,
            state = rememberWindowState(size = DpSize(440.dp, 900.dp)),
        ) {
            GoldenPawApp(launchRequests = launchRequests, onLaunchRequestHandled = { launchRequests.value = null })
        }
    }
}

/** App and tray icon: the paw mark on a honey circle. */
private object PawPainter : Painter() {
    override val intrinsicSize: Size = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        drawCircle(Color(0xFFE8A33D))
        drawPaw(Color.White, Offset(size.width / 2, size.height / 2), size.minDimension * 0.62f)
    }
}
