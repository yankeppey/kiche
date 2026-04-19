package eu.buney.kiche.ktor.webtransport.capsule

/**
 * HTTP Capsule Protocol (RFC 9297) implementation.
 *
 * A capsule is a TLV (type-length-value) frame sent on an HTTP CONNECT stream:
 * ```
 * Capsule {
 *   Type   (varint)   // QUIC variable-length integer
 *   Length  (varint)   // payload length in bytes
 *   Value   (bytes)    // Length bytes of payload
 * }
 * ```
 *
 * Known capsule types:
 * - `DATAGRAM (0x00)` — RFC 9297, carries an HTTP Datagram payload
 * - `CLOSE_WEBTRANSPORT_SESSION (0x2843)` — WebTransport session close
 */
public object CapsuleType {
    /** RFC 9297 §5.4 — carries an HTTP Datagram payload on the CONNECT stream. */
    public const val DATAGRAM: Long = 0x00L

    /** draft-ietf-webtrans-http3 — signals session closure with error code + reason. */
    public const val CLOSE_WEBTRANSPORT_SESSION: Long = 0x2843L
}

/**
 * A parsed capsule header + value.
 */
public class Capsule(
    /** The capsule type (QUIC varint). */
    public val type: Long,
    /** The capsule payload. */
    public val value: ByteArray,
)

/**
 * Writes a capsule (type + length + value) into a new [ByteArray].
 */
public fun writeCapsule(type: Long, value: ByteArray): ByteArray {
    val typeLen = QuicVarint.encodedLength(type)
    val lengthLen = QuicVarint.encodedLength(value.size.toLong())
    val buf = ByteArray(typeLen + lengthLen + value.size)
    var offset = 0
    offset += QuicVarint.encode(type, buf, offset)
    offset += QuicVarint.encode(value.size.toLong(), buf, offset)
    value.copyInto(buf, offset)
    return buf
}

/**
 * Parses a single capsule from [buf] starting at [offset].
 *
 * @return Pair of (parsed Capsule, total bytes consumed), or null if
 *   the buffer does not contain a complete capsule.
 * @throws CapsuleParseException if the header is partially present
 *   but the value is truncated.
 */
public fun parseCapsule(buf: ByteArray, offset: Int = 0): Pair<Capsule, Int>? {
    // Parse type varint
    val (type, typeLen) = QuicVarint.decode(buf, offset) ?: return null

    // Parse length varint
    val (length, lengthLen) = QuicVarint.decode(buf, offset + typeLen)
        ?: throw CapsuleParseException("Truncated capsule: type parsed but length incomplete")

    val headerLen = typeLen + lengthLen
    val totalLen = headerLen + length.toInt()

    if (offset + totalLen > buf.size) {
        throw CapsuleParseException(
            "Truncated capsule: need $length value bytes but only ${buf.size - offset - headerLen} available"
        )
    }

    val value = buf.copyOfRange(offset + headerLen, offset + totalLen)
    return Capsule(type, value) to totalLen
}

/**
 * Parses all capsules from [buf]. Stops when no more complete capsules
 * can be parsed (remaining bytes are returned as leftover).
 *
 * @return Pair of (list of parsed capsules, number of bytes consumed).
 */
public fun parseAllCapsules(buf: ByteArray): Pair<List<Capsule>, Int> {
    val capsules = mutableListOf<Capsule>()
    var offset = 0
    while (offset < buf.size) {
        val result = try {
            parseCapsule(buf, offset)
        } catch (_: CapsuleParseException) {
            break
        }
        if (result == null) break
        capsules.add(result.first)
        offset += result.second
    }
    return capsules to offset
}

public class CapsuleParseException(message: String) : Exception(message)
