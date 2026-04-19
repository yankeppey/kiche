package eu.buney.kiche

/**
 * QUIC variable-length integer encoding/decoding (RFC 9000, Section 16).
 *
 * Encodes unsigned integers up to 2^62-1 using 1, 2, 4, or 8 bytes.
 * The two most significant bits of the first byte indicate the length.
 */
public object QuicVarint {

    /**
     * Encodes [value] as a QUIC variable-length integer and appends to [out].
     *
     * @throws IllegalArgumentException if value is negative or >= 2^62.
     */
    public fun encode(value: Long, out: ByteArray, offset: Int): Int {
        require(value >= 0) { "Varint value must be non-negative: $value" }
        return when {
            value < 0x40 -> {
                out[offset] = value.toByte()
                1
            }
            value < 0x4000 -> {
                out[offset] = ((value shr 8) or 0x40).toByte()
                out[offset + 1] = (value and 0xFF).toByte()
                2
            }
            value < 0x40000000 -> {
                out[offset] = ((value shr 24) or 0x80).toByte()
                out[offset + 1] = ((value shr 16) and 0xFF).toByte()
                out[offset + 2] = ((value shr 8) and 0xFF).toByte()
                out[offset + 3] = (value and 0xFF).toByte()
                4
            }
            else -> {
                require(value < (1L shl 62)) { "Varint value too large: $value" }
                out[offset] = ((value ushr 56) or 0xC0).toByte()
                out[offset + 1] = ((value ushr 48) and 0xFF).toByte()
                out[offset + 2] = ((value ushr 40) and 0xFF).toByte()
                out[offset + 3] = ((value ushr 32) and 0xFF).toByte()
                out[offset + 4] = ((value ushr 24) and 0xFF).toByte()
                out[offset + 5] = ((value ushr 16) and 0xFF).toByte()
                out[offset + 6] = ((value ushr 8) and 0xFF).toByte()
                out[offset + 7] = (value and 0xFF).toByte()
                8
            }
        }
    }

    /** Encodes [value] as a QUIC variable-length integer and returns the bytes. */
    public fun encode(value: Long): ByteArray {
        val buf = ByteArray(8)
        val len = encode(value, buf, 0)
        return buf.copyOf(len)
    }

    /**
     * Decodes a QUIC variable-length integer from [buf] starting at [offset].
     *
     * @return Pair of (decoded value, number of bytes consumed), or null if
     *   there are not enough bytes remaining.
     */
    public fun decode(buf: ByteArray, offset: Int): Pair<Long, Int>? {
        if (offset >= buf.size) return null
        val first = buf[offset].toInt() and 0xFF
        val len = 1 shl (first shr 6) // 1, 2, 4, or 8

        if (offset + len > buf.size) return null

        var value = (first and 0x3F).toLong()
        for (i in 1 until len) {
            value = (value shl 8) or (buf[offset + i].toInt() and 0xFF).toLong()
        }
        return value to len
    }

    /** Returns the number of bytes needed to encode [value]. */
    public fun encodedLength(value: Long): Int = when {
        value < 0x40 -> 1
        value < 0x4000 -> 2
        value < 0x40000000 -> 4
        else -> 8
    }
}
