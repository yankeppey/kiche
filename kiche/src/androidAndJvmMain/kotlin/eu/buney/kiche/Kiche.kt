package eu.buney.kiche

actual object Kiche {
    init {
        KicheLoader.load()
    }

    actual fun quicheVersion(): String = nativeQuicheVersion()
    actual fun versionIsSupported(version: UInt): Boolean = nativeVersionIsSupported(version.toInt())
    actual fun enableDebugLogging(): Boolean = nativeEnableDebugLogging() == 0

    actual fun headerInfo(buf: ByteArray, len: Int, dcil: Int): KicheHeaderInfo {
        val meta = IntArray(2)          // [version, type]
        val scid = ByteArray(20)        // QUICHE_MAX_CONN_ID_LEN
        val dcid = ByteArray(20)
        val token = ByteArray(512)
        val lens = IntArray(3)          // [scidLen, dcidLen, tokenLen]

        val rc = nativeHeaderInfo(buf, len, dcil, meta, scid, dcid, token, lens)
        if (rc < 0) KicheException.check(rc)

        return KicheHeaderInfo(
            version = meta[0].toUInt(),
            type = KichePacketType.fromValue(meta[1]),
            scid = scid.copyOf(lens[0]),
            dcid = dcid.copyOf(lens[1]),
            token = token.copyOf(lens[2]),
        )
    }

    actual fun negotiateVersion(scid: ByteArray, dcid: ByteArray, out: ByteArray): Int {
        val rc = nativeNegotiateVersion(scid, dcid, out)
        if (rc < 0) KicheException.check(rc)
        return rc
    }

    actual fun retry(
        scid: ByteArray, dcid: ByteArray, newScid: ByteArray,
        token: ByteArray, version: UInt, out: ByteArray,
    ): Int {
        val rc = nativeRetry(scid, dcid, newScid, token, version.toInt(), out)
        if (rc < 0) KicheException.check(rc)
        return rc
    }

    @JvmStatic
    private external fun nativeQuicheVersion(): String
    @JvmStatic
    private external fun nativeVersionIsSupported(version: Int): Boolean
    @JvmStatic
    private external fun nativeEnableDebugLogging(): Int
    @JvmStatic
    private external fun nativeHeaderInfo(buf: ByteArray, len: Int, dcil: Int,
        outMeta: IntArray, outScid: ByteArray, outDcid: ByteArray, outToken: ByteArray,
        outLens: IntArray): Int
    @JvmStatic
    private external fun nativeNegotiateVersion(scid: ByteArray, dcid: ByteArray, out: ByteArray): Int
    @JvmStatic
    private external fun nativeRetry(scid: ByteArray, dcid: ByteArray, newScid: ByteArray,
        token: ByteArray, version: Int, out: ByteArray): Int
}
