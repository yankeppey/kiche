package eu.buney.kiche

expect object Kiche {
    fun quicheVersion(): String
    fun versionIsSupported(version: UInt): Boolean
    /**
     * Enables quiche's internal debug logging. Log lines are written to stderr
     * (visible in logcat on Android, Xcode console on iOS, terminal on JVM).
     *
     * This can only be called once — quiche registers a global logger internally.
     * Call early, before creating any connections.
     *
     * @return `true` if logging was enabled, `false` if it was already enabled by a prior call.
     */
    fun enableDebugLogging(): Boolean
}
