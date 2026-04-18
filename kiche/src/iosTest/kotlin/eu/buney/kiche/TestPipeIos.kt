package eu.buney.kiche

actual fun quicheCertDir(): String {
    // iOS simulator tests run on the host Mac and have full filesystem access.
    // Use the absolute path to the quiche examples directory.
    // This is injected at build time via KICHE_QUICHE_CERT_DIR.
    return KICHE_QUICHE_CERT_DIR
}
