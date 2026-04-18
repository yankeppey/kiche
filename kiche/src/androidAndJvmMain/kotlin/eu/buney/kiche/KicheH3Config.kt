package eu.buney.kiche

actual class KicheH3Config actual constructor() : AutoCloseable {
    private var handle: Long

    init {
        KicheLoader.load()
        handle = nativeNew()
        if (handle == 0L) error("Failed to create quiche_h3_config")
    }

    private fun requireOpen(): Long {
        val h = handle
        check(h != 0L) { "KicheH3Config is closed" }
        return h
    }

    actual fun setMaxFieldSectionSize(v: Long) = nativeSetMaxFieldSectionSize(requireOpen(), v)
    actual fun setQpackMaxTableCapacity(v: Long) = nativeSetQpackMaxTableCapacity(requireOpen(), v)
    actual fun setQpackBlockedStreams(v: Long) = nativeSetQpackBlockedStreams(requireOpen(), v)
    actual fun enableExtendedConnect(enabled: Boolean) = nativeEnableExtendedConnect(requireOpen(), enabled)

    actual override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeFree(h)
        }
    }

    internal fun getHandle(): Long = requireOpen()

    private external fun nativeNew(): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeSetMaxFieldSectionSize(handle: Long, v: Long)
    private external fun nativeSetQpackMaxTableCapacity(handle: Long, v: Long)
    private external fun nativeSetQpackBlockedStreams(handle: Long, v: Long)
    private external fun nativeEnableExtendedConnect(handle: Long, enabled: Boolean)
}
