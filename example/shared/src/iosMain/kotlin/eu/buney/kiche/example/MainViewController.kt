package eu.buney.kiche.example

import androidx.compose.ui.window.ComposeUIViewController

/**
 * iOS entry point. The Swift app (example/iosApp) wraps this UIViewController in a
 * UIViewControllerRepresentable. Exposed to Swift as `MainViewControllerKt.MainViewController()`.
 */
fun MainViewController() = ComposeUIViewController { App() }
