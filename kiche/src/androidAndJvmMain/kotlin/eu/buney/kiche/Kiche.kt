package eu.buney.kiche

actual object Kiche {
    init {
        KicheLoader.load()
    }

    actual fun quicheVersion(): String = nativeQuicheVersion()
    actual fun versionIsSupported(version: UInt): Boolean = nativeVersionIsSupported(version.toInt())

    @JvmStatic
    private external fun nativeQuicheVersion(): String
    @JvmStatic
    private external fun nativeVersionIsSupported(version: Int): Boolean
}
