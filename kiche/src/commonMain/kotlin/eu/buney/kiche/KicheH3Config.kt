package eu.buney.kiche

expect class KicheH3Config() : AutoCloseable {
    fun setMaxFieldSectionSize(v: Long)
    fun setQpackMaxTableCapacity(v: Long)
    fun setQpackBlockedStreams(v: Long)
    fun enableExtendedConnect(enabled: Boolean)
    override fun close()
}
