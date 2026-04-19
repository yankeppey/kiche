package eu.buney.kiche.ktor.webtransport.capsule

import eu.buney.kiche.ktor.webtransport.WebTransportCloseInfo

/**
 * CLOSE_WEBTRANSPORT_SESSION capsule (type 0x2843).
 *
 * Sent on the CONNECT stream body to signal WebTransport session closure.
 *
 * Wire format:
 * ```
 * Capsule Type = 0x2843 (varint)
 * Capsule Length (varint)
 * Capsule Value {
 *   Error Code (32-bit big-endian unsigned int)
 *   Reason Phrase (UTF-8 string, max 1024 bytes)
 * }
 * ```
 */
public object CloseWebTransportSession {

    public const val MAX_REASON_LENGTH: Int = 1024

    /**
     * Encodes a CLOSE_WEBTRANSPORT_SESSION capsule as a [ByteArray]
     * ready to send via `h3Conn.sendBody()` on the CONNECT stream.
     */
    public fun encode(info: WebTransportCloseInfo): ByteArray {
        val reasonBytes = info.reason.encodeToByteArray()
        val truncatedReason = truncateUtf8(reasonBytes, MAX_REASON_LENGTH)
        val payload = ByteArray(4 + truncatedReason.size)

        // 4-byte big-endian error code
        val code = info.code
        payload[0] = (code shr 24).toByte()
        payload[1] = (code shr 16).toByte()
        payload[2] = (code shr 8).toByte()
        payload[3] = code.toByte()

        // UTF-8 reason string
        truncatedReason.copyInto(payload, 4)

        return writeCapsule(CapsuleType.CLOSE_WEBTRANSPORT_SESSION, payload)
    }

    /**
     * Decodes a CLOSE_WEBTRANSPORT_SESSION capsule payload into [WebTransportCloseInfo].
     *
     * @param payload The capsule value (not including type/length header).
     * @return The parsed close info, or null if the payload is malformed.
     */
    public fun decode(payload: ByteArray): WebTransportCloseInfo? {
        if (payload.size < 4) return null

        val code = ((payload[0].toInt() and 0xFF) shl 24) or
            ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or
            (payload[3].toInt() and 0xFF)

        val reasonBytes = if (payload.size > 4) {
            payload.copyOfRange(4, minOf(payload.size, 4 + MAX_REASON_LENGTH))
        } else {
            ByteArray(0)
        }

        val reason = try {
            reasonBytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Throwable) {
            return null // Invalid UTF-8
        }

        return WebTransportCloseInfo(code.toUInt(), reason)
    }
}

/**
 * Truncates a UTF-8 byte array to at most [maxBytes] without splitting
 * multi-byte characters.
 */
internal fun truncateUtf8(bytes: ByteArray, maxBytes: Int): ByteArray {
    if (bytes.size <= maxBytes) return bytes

    // Walk backwards from maxBytes to find a valid UTF-8 boundary
    var end = maxBytes
    while (end > 0) {
        val b = bytes[end].toInt() and 0xFF
        // If this byte is a continuation byte (10xxxxxx), keep going back
        if (b and 0xC0 == 0x80) {
            end--
        } else {
            // This is a start byte — check if the full character fits
            val charLen = when {
                b and 0x80 == 0x00 -> 1  // 0xxxxxxx — ASCII
                b and 0xE0 == 0xC0 -> 2  // 110xxxxx
                b and 0xF0 == 0xE0 -> 3  // 1110xxxx
                b and 0xF8 == 0xF0 -> 4  // 11110xxx
                else -> 1 // Invalid — treat as single byte
            }
            if (end + charLen <= maxBytes) {
                end += charLen
            }
            break
        }
    }
    return bytes.copyOf(end)
}
