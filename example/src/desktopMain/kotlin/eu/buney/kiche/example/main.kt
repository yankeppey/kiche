package eu.buney.kiche.example

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Kiche — HTTP/3 demo") {
        App()
    }
}
