package eu.buney.kiche.ktor

actual fun quicheCertDir(): String {
    // iOS simulator tests run on the host Mac with filesystem access.
    // TODO: inject path at build time like the core kiche module does
    error("iOS cert dir not configured yet for ktor-client-kiche tests")
}
