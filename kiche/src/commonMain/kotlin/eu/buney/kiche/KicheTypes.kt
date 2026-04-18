package eu.buney.kiche

enum class KicheCcAlgorithm(val value: Int) {
    Reno(0),
    Cubic(1),
    Bbr2(4);
}

enum class KicheShutdown(val value: Int) {
    Read(0),
    Write(1);
}

class KicheAddress(
    val ip: ByteArray, // 4 bytes (IPv4) or 16 bytes (IPv6)
    val port: Int,
) {
    val isIpv6: Boolean get() = ip.size == 16
}

data class KicheSendResult(
    val written: Int,
    val from: KicheAddress,
    val to: KicheAddress,
    val atNanos: Long,
)

data class KicheStreamRecvResult(
    val read: Int,
    val fin: Boolean,
)

data class KicheConnectionError(
    val isApp: Boolean,
    val errorCode: Long,
    val reason: ByteArray,
)

data class KicheStats(
    val recv: Long,
    val sent: Long,
    val lost: Long,
    val spuriousLost: Long,
    val retrans: Long,
    val sentBytes: Long,
    val recvBytes: Long,
    val ackedBytes: Long,
    val lostBytes: Long,
    val streamRetransBytes: Long,
    val dgramRecv: Long,
    val dgramSent: Long,
    val pathsCount: Long,
    val resetStreamCountLocal: Long,
    val stoppedStreamCountLocal: Long,
    val resetStreamCountRemote: Long,
    val stoppedStreamCountRemote: Long,
)

data class KichePathStats(
    val localAddr: KicheAddress,
    val peerAddr: KicheAddress,
    val active: Boolean,
    val recv: Long,
    val sent: Long,
    val lost: Long,
    val retrans: Long,
    val rtt: Long,
    val minRtt: Long,
    val rttvar: Long,
    val cwnd: Long,
    val sentBytes: Long,
    val recvBytes: Long,
    val lostBytes: Long,
    val streamRetransBytes: Long,
    val pmtu: Long,
    val deliveryRate: Long,
)

data class KicheTransportParams(
    val peerMaxIdleTimeout: Long,
    val peerMaxUdpPayloadSize: Long,
    val peerInitialMaxData: Long,
    val peerInitialMaxStreamDataBidiLocal: Long,
    val peerInitialMaxStreamDataBidiRemote: Long,
    val peerInitialMaxStreamDataUni: Long,
    val peerInitialMaxStreamsBidi: Long,
    val peerInitialMaxStreamsUni: Long,
    val peerAckDelayExponent: Long,
    val peerMaxAckDelay: Long,
    val peerDisableActiveMigration: Boolean,
    val peerActiveConnIdLimit: Long,
    val peerMaxDatagramFrameSize: Long,
)
