package com.harmony.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.harmony.desktop.ui.HarmonyDesktopApp

fun main() {
    val app = DesktopApp.create()
    application {
        Window(
            onCloseRequest = { app.close(); exitApplication() },
            title = "Harmony",
            state = rememberWindowState(size = DpSize(1280.dp, 820.dp)),
        ) {
            HarmonyDesktopApp(app)
        }
    }
}
