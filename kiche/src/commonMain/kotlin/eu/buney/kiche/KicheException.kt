package eu.buney.kiche

class KicheException(
    val error: KicheError,
    message: String = "quiche error: ${error.name} (${error.code})",
) : Exception(message) {

    companion object {
        fun check(code: Int) {
            if (code < 0) {
                val error = KicheError.fromCode(code)
                    ?: throw KicheException(KicheError.Done, "Unknown quiche error code: $code")
                if (error != KicheError.Done) {
                    throw KicheException(error)
                }
            }
        }
    }
}
