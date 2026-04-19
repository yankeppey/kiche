package eu.buney.kiche

actual object Kiche {
    init {
        KicheLoader.load()
    }

    actual fun quicheVersion(): String = nativeQuicheVersion()
    actual fun versionIsSupported(version: UInt): Boolean = nativeVersionIsSupported(version.toInt())
    actual fun enableDebugLogging(): Boolean = nativeEnableDebugLogging() == 0

    @JvmStatic
    private external fun nativeQuicheVersion(): String
    @JvmStatic
    private external fun nativeVersionIsSupported(version: Int): Boolean
    @JvmStatic
    private external fun nativeEnableDebugLogging(): Int
}
