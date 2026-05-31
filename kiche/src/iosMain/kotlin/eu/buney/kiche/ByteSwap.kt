package eu.buney.kiche

// Network byte order conversion for 16-bit values. iOS targets are
// little-endian (arm64 + x64 simulator), so both directions are the same
// byte swap. Equivalent to the C `htons(v) = (v >> 8) | (v << 8)` with a
// `uint16_t` cast at the end.
internal fun hostToNetShort(v: UShort): UShort {
    val i = v.toInt()
    return ((i shr 8) or (i shl 8)).toUShort()
}

internal fun netToHostShort(v: UShort): UShort = hostToNetShort(v)
