package eu.buney.kiche

actual class KicheConfig actual constructor(version: UInt) : AutoCloseable {
    private var handle: Long

    init {
        KicheLoader.load()
        handle = nativeNew(version.toInt())
        if (handle == 0L) error("Failed to create quiche_config")
    }

    private fun requireOpen(): Long {
        val h = handle
        check(h != 0L) { "KicheConfig is closed" }
        return h
    }

    actual fun loadCertChainFromPemFile(path: String) {
        KicheException.check(nativeLoadCertChainFromPemFile(requireOpen(), path))
    }

    actual fun loadPrivKeyFromPemFile(path: String) {
        KicheException.check(nativeLoadPrivKeyFromPemFile(requireOpen(), path))
    }

    actual fun loadVerifyLocationsFromFile(path: String) {
        KicheException.check(nativeLoadVerifyLocationsFromFile(requireOpen(), path))
    }

    actual fun loadVerifyLocationsFromDirectory(path: String) {
        KicheException.check(nativeLoadVerifyLocationsFromDirectory(requireOpen(), path))
    }

    actual fun verifyPeer(verify: Boolean) = nativeVerifyPeer(requireOpen(), verify)
    actual fun grease(enabled: Boolean) = nativeGrease(requireOpen(), enabled)
    actual fun discoverPmtu(enabled: Boolean) = nativeDiscoverPmtu(requireOpen(), enabled)
    actual fun logKeys() = nativeLogKeys(requireOpen())
    actual fun enableEarlyData() = nativeEnableEarlyData(requireOpen())

    actual fun setApplicationProtos(protos: ByteArray) {
        KicheException.check(nativeSetApplicationProtos(requireOpen(), protos))
    }

    actual fun setMaxAmplificationFactor(factor: Long) = nativeSetMaxAmplificationFactor(requireOpen(), factor)
    actual fun setMaxIdleTimeout(millis: Long) = nativeSetMaxIdleTimeout(requireOpen(), millis)
    actual fun setMaxRecvUdpPayloadSize(size: Long) = nativeSetMaxRecvUdpPayloadSize(requireOpen(), size)
    actual fun setMaxSendUdpPayloadSize(size: Long) = nativeSetMaxSendUdpPayloadSize(requireOpen(), size)
    actual fun setInitialMaxData(v: Long) = nativeSetInitialMaxData(requireOpen(), v)
    actual fun setInitialMaxStreamDataBidiLocal(v: Long) = nativeSetInitialMaxStreamDataBidiLocal(requireOpen(), v)
    actual fun setInitialMaxStreamDataBidiRemote(v: Long) = nativeSetInitialMaxStreamDataBidiRemote(requireOpen(), v)
    actual fun setInitialMaxStreamDataUni(v: Long) = nativeSetInitialMaxStreamDataUni(requireOpen(), v)
    actual fun setInitialMaxStreamsBidi(v: Long) = nativeSetInitialMaxStreamsBidi(requireOpen(), v)
    actual fun setInitialMaxStreamsUni(v: Long) = nativeSetInitialMaxStreamsUni(requireOpen(), v)
    actual fun setAckDelayExponent(v: Long) = nativeSetAckDelayExponent(requireOpen(), v)
    actual fun setMaxAckDelay(v: Long) = nativeSetMaxAckDelay(requireOpen(), v)
    actual fun setDisableActiveMigration(disabled: Boolean) = nativeSetDisableActiveMigration(requireOpen(), disabled)

    actual fun setCcAlgorithm(algo: KicheCcAlgorithm) = nativeSetCcAlgorithm(requireOpen(), algo.value)

    actual fun setInitialCongestionWindowPackets(packets: Long) =
        nativeSetInitialCongestionWindowPackets(requireOpen(), packets)

    actual fun enableHystart(enabled: Boolean) = nativeEnableHystart(requireOpen(), enabled)
    actual fun enablePacing(enabled: Boolean) = nativeEnablePacing(requireOpen(), enabled)
    actual fun setMaxPacingRate(v: Long) = nativeSetMaxPacingRate(requireOpen(), v)

    actual fun enableDgram(enabled: Boolean, recvQueueLen: Long, sendQueueLen: Long) =
        nativeEnableDgram(requireOpen(), enabled, recvQueueLen, sendQueueLen)

    actual fun setMaxConnectionWindow(v: Long) = nativeSetMaxConnectionWindow(requireOpen(), v)
    actual fun setMaxStreamWindow(v: Long) = nativeSetMaxStreamWindow(requireOpen(), v)
    actual fun setActiveConnectionIdLimit(v: Long) = nativeSetActiveConnectionIdLimit(requireOpen(), v)

    actual fun setStatelessResetToken(token: ByteArray) {
        require(token.size == 16) { "Stateless reset token must be 16 bytes" }
        nativeSetStatelessResetToken(requireOpen(), token)
    }

    actual fun setDisableDcidReuse(disabled: Boolean) = nativeSetDisableDcidReuse(requireOpen(), disabled)

    actual fun setTicketKey(key: ByteArray) {
        KicheException.check(nativeSetTicketKey(requireOpen(), key))
    }
    actual fun setEnableCubicIdleRestartFix(enabled: Boolean) = nativeSetEnableCubicIdleRestartFix(requireOpen(), enabled)

    actual override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeFree(h)
        }
    }

    // Expose handle for KicheConnection to use
    internal fun getHandle(): Long = requireOpen()

    //region Native methods
    private external fun nativeNew(version: Int): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeLoadCertChainFromPemFile(handle: Long, path: String): Int
    private external fun nativeLoadPrivKeyFromPemFile(handle: Long, path: String): Int
    private external fun nativeLoadVerifyLocationsFromFile(handle: Long, path: String): Int
    private external fun nativeLoadVerifyLocationsFromDirectory(handle: Long, path: String): Int
    private external fun nativeVerifyPeer(handle: Long, verify: Boolean)
    private external fun nativeGrease(handle: Long, enabled: Boolean)
    private external fun nativeDiscoverPmtu(handle: Long, enabled: Boolean)
    private external fun nativeLogKeys(handle: Long)
    private external fun nativeEnableEarlyData(handle: Long)
    private external fun nativeSetApplicationProtos(handle: Long, protos: ByteArray): Int
    private external fun nativeSetMaxAmplificationFactor(handle: Long, factor: Long)
    private external fun nativeSetMaxIdleTimeout(handle: Long, millis: Long)
    private external fun nativeSetMaxRecvUdpPayloadSize(handle: Long, size: Long)
    private external fun nativeSetMaxSendUdpPayloadSize(handle: Long, size: Long)
    private external fun nativeSetInitialMaxData(handle: Long, v: Long)
    private external fun nativeSetInitialMaxStreamDataBidiLocal(handle: Long, v: Long)
    private external fun nativeSetInitialMaxStreamDataBidiRemote(handle: Long, v: Long)
    private external fun nativeSetInitialMaxStreamDataUni(handle: Long, v: Long)
    private external fun nativeSetInitialMaxStreamsBidi(handle: Long, v: Long)
    private external fun nativeSetInitialMaxStreamsUni(handle: Long, v: Long)
    private external fun nativeSetAckDelayExponent(handle: Long, v: Long)
    private external fun nativeSetMaxAckDelay(handle: Long, v: Long)
    private external fun nativeSetDisableActiveMigration(handle: Long, disabled: Boolean)
    private external fun nativeSetCcAlgorithm(handle: Long, algo: Int)
    private external fun nativeSetInitialCongestionWindowPackets(handle: Long, packets: Long)
    private external fun nativeEnableHystart(handle: Long, enabled: Boolean)
    private external fun nativeEnablePacing(handle: Long, enabled: Boolean)
    private external fun nativeSetMaxPacingRate(handle: Long, v: Long)
    private external fun nativeEnableDgram(handle: Long, enabled: Boolean, recvQueueLen: Long, sendQueueLen: Long)
    private external fun nativeSetMaxConnectionWindow(handle: Long, v: Long)
    private external fun nativeSetMaxStreamWindow(handle: Long, v: Long)
    private external fun nativeSetActiveConnectionIdLimit(handle: Long, v: Long)
    private external fun nativeSetStatelessResetToken(handle: Long, token: ByteArray)
    private external fun nativeSetDisableDcidReuse(handle: Long, disabled: Boolean)
    private external fun nativeSetTicketKey(handle: Long, key: ByteArray): Int
    private external fun nativeSetEnableCubicIdleRestartFix(handle: Long, enabled: Boolean)
    //endregion
}
