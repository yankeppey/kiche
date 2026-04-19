package eu.buney.kiche.ktor.webtransport.capsule

/**
 * HTTP Datagram framing for HTTP/3 (RFC 9297, Section 3.1).
 *
 * When sending a QUIC DATAGRAM frame on an HTTP/3 connection that has
 * negotiated `SETTINGS_H3_DATAGRAM`, the payload must be prefixed with
 * a Quarter Stream ID (the CONNECT stream's ID divided by 4, varint-encoded).
 *
 * ```
 * QUIC DATAGRAM frame payload:
 *   Quarter Stream ID (varint)
 *   HTTP Datagram Payload (remaining bytes)
 * ```
 */
public object HttpDatagram {

    /**
     * Encodes an HTTP Datagram: prepends the Quarter Stream ID to the payload.
     *
     * @param connectStreamId The H3 stream ID of the CONNECT request.
     * @param payload The application datagram payload.
     * @return The framed datagram ready for `conn.dgramSend()`.
     */
    public fun encode(connectStreamId: Long, payload: ByteArray): ByteArray {
        val quarterStreamId = connectStreamId / 4
        val qsidBytes = QuicVarint.encode(quarterStreamId)
        val result = ByteArray(qsidBytes.size + payload.size)
        qsidBytes.copyInto(result)
        payload.copyInto(result, qsidBytes.size)
        return result
    }

    /**
     * Decodes an HTTP Datagram: strips the Quarter Stream ID prefix.
     *
     * @param data The raw QUIC DATAGRAM frame payload from `conn.dgramRecv()`.
     * @return Pair of (Quarter Stream ID * 4 = original stream ID, application payload),
     *   or null if the data is too short to contain a valid varint.
     */
    public fun decode(data: ByteArray): Pair<Long, ByteArray>? {
        val (quarterStreamId, len) = QuicVarint.decode(data, 0) ?: return null
        val connectStreamId = quarterStreamId * 4
        val payload = data.copyOfRange(len, data.size)
        return connectStreamId to payload
    }
}
