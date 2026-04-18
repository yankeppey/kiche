package eu.buney.kiche

actual object Kiche {
    init {
        KicheLoader.load()
    }

    actual fun quicheVersion(): String = nativeQuicheVersion()

    @JvmStatic
    private external fun nativeQuicheVersion(): String
}
