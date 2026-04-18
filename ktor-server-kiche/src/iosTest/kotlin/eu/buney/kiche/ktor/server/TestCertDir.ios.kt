package eu.buney.kiche.ktor.server

actual fun quicheCertDir(): String {
    error("iOS cert dir not configured yet for ktor-server-kiche tests")
}
