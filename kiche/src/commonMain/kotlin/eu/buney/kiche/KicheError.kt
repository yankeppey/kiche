package eu.buney.kiche

enum class KicheError(val code: Int) {
    Done(-1),
    BufferTooShort(-2),
    UnknownVersion(-3),
    InvalidFrame(-4),
    InvalidPacket(-5),
    InvalidState(-6),
    InvalidStreamState(-7),
    InvalidTransportParam(-8),
    CryptoFail(-9),
    TlsFail(-10),
    FlowControl(-11),
    StreamLimit(-12),
    FinalSize(-13),
    CongestionControl(-14),
    StreamStopped(-15),
    StreamReset(-16),
    IdLimit(-17),
    OutOfIdentifiers(-18),
    KeyUpdate(-19),
    CryptoBufferExceeded(-20),
    InvalidAckRange(-21),
    OptimisticAckDetected(-22),
    InvalidDcidInitialization(-23);

    /** True for errors that indicate temporary backpressure (retry after draining). */
    val isRetryable: Boolean
        get() = this == Done || this == FlowControl

    companion object {
        fun fromCode(code: Int): KicheError? = entries.find { it.code == code }
    }
}
