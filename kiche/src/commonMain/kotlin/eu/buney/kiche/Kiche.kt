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

    /**
     * Extracts version, type, source/destination connection ID, and token
     * from a QUIC packet header. Used for server-side packet routing before
     * a connection is created.
     *
     * @param buf the raw UDP packet bytes
     * @param len number of bytes to read from [buf]
     * @param dcil the expected destination connection ID length (server-chosen)
     */
    fun headerInfo(buf: ByteArray, len: Int, dcil: Int): KicheHeaderInfo

    /**
     * Writes a Version Negotiation packet into [out].
     *
     * @return the number of bytes written, or throws on error.
     */
    fun negotiateVersion(scid: ByteArray, dcid: ByteArray, out: ByteArray): Int

    /**
     * Writes a Retry packet into [out] for address validation.
     *
     * @param scid the client's original source connection ID
     * @param dcid the client's original destination connection ID
     * @param newScid the server's new source connection ID for the retry
     * @param token the address validation token
     * @param version the QUIC version
     * @return the number of bytes written, or throws on error.
     */
    fun retry(
        scid: ByteArray, dcid: ByteArray, newScid: ByteArray,
        token: ByteArray, version: UInt, out: ByteArray,
    ): Int
}
