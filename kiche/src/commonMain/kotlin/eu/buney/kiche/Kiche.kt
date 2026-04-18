package eu.buney.kiche

expect object Kiche {
    fun quicheVersion(): String
    fun versionIsSupported(version: UInt): Boolean
}
