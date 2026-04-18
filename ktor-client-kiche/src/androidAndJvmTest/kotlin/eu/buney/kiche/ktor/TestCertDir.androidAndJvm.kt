package eu.buney.kiche.ktor

import java.io.File

actual fun quicheCertDir(): String {
    val candidates = listOf(
        "third_party/quiche/quiche/examples",
        "../third_party/quiche/quiche/examples",
    )
    for (path in candidates) {
        if (File(path, "cert.crt").exists()) return path
    }
    error("Cannot find quiche example certs. Searched: $candidates")
}
