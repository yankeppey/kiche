package eu.buney.kiche

import kotlin.jvm.JvmStatic

class KicheException(
    val error: KicheError,
    message: String = "quiche error: ${error.name} (${error.code})",
) : Exception(message) {

    companion object {
        @JvmStatic
        fun check(code: Int) {
            if (code < 0) {
                val error = KicheError.fromCode(code)
                    ?: throw KicheException(KicheError.Done, "Unknown quiche error code: $code")
                if (error != KicheError.Done) {
                    throw KicheException(error)
                }
            }
        }

        /**
         * Like [check], but also throws on [KicheError.Done].
         * Use for operations where Done means "rejected" (e.g., second close, queue full).
         */
        @JvmStatic
        fun checkStrict(code: Int) {
            if (code < 0) {
                val error = KicheError.fromCode(code)
                    ?: throw KicheException(KicheError.Done, "Unknown quiche error code: $code")
                throw KicheException(error)
            }
        }
    }
}
