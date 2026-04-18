package eu.buney.kiche

enum class KicheH3EventType(val value: Int) {
    Headers(0),
    Data(1),
    Finished(2),
    GoAway(3),
    Reset(4),
    PriorityUpdate(5);

    companion object {
        fun fromValue(value: Int): KicheH3EventType? = entries.find { it.value == value }
    }
}

enum class KicheH3Error(val code: Int) {
    Done(-1),
    BufferTooShort(-2),
    InternalError(-3),
    ExcessiveLoad(-4),
    IdError(-5),
    StreamCreationError(-6),
    ClosedCriticalStream(-7),
    MissingSettings(-8),
    FrameUnexpected(-9),
    FrameError(-10),
    QpackDecompressionFailed(-11),
    StreamBlocked(-13),
    SettingsError(-14),
    RequestRejected(-15),
    RequestCancelled(-16),
    RequestIncomplete(-17),
    MessageError(-18),
    ConnectError(-19),
    VersionFallback(-20);

    companion object {
        fun fromCode(code: Int): KicheH3Error? = entries.find { it.code == code }
    }
}

class KicheH3Header(
    val name: ByteArray,
    val value: ByteArray,
) {
    constructor(name: String, value: String) : this(name.encodeToByteArray(), value.encodeToByteArray())

    val nameString: String get() = name.decodeToString()
    val valueString: String get() = value.decodeToString()
}

data class KicheH3Stats(
    val qpackEncoderStreamRecvBytes: Long,
    val qpackDecoderStreamRecvBytes: Long,
)

data class KicheH3Event(
    val type: KicheH3EventType,
    val streamId: Long,
    val headers: List<KicheH3Header>?,
)
