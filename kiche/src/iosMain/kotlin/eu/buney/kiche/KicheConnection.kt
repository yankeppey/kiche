@file:OptIn(ExperimentalForeignApi::class)

package eu.buney.kiche

import kotlinx.cinterop.*
import platform.posix.*
import quiche.c.*
import quiche.c.quiche_path_event_type.*

actual class KicheConnection private constructor(internal var ptr: COpaquePointer?) : AutoCloseable {

    private fun conn(): CPointer<cnames.structs.quiche_conn> =
        (ptr ?: error("KicheConnection is closed")).reinterpret()

    actual companion object {
        actual fun connect(
            serverName: String,
            scid: ByteArray,
            local: KicheAddress,
            peer: KicheAddress,
            config: KicheConfig,
        ): KicheConnection = memScoped {
            val localSs = alloc<sockaddr_storage>()
            val localLen = fillSockaddr(localSs.ptr, local)
            val peerSs = alloc<sockaddr_storage>()
            val peerLen = fillSockaddr(peerSs.ptr, peer)

            scid.usePinned { pinnedScid ->
                val connPtr = quiche_connect(
                    serverName,
                    pinnedScid.addressOf(0).reinterpret(),
                    scid.size.toULong(),
                    localSs.ptr.reinterpret(),
                    localLen,
                    peerSs.ptr.reinterpret(),
                    peerLen,
                    config.ptr?.reinterpret()
                ) ?: error("Failed to create QUIC connection")
                KicheConnection(connPtr)
            }
        }

        actual fun accept(
            scid: ByteArray,
            odcid: ByteArray?,
            local: KicheAddress,
            peer: KicheAddress,
            config: KicheConfig,
        ): KicheConnection = memScoped {
            val localSs = alloc<sockaddr_storage>()
            val localLen = fillSockaddr(localSs.ptr, local)
            val peerSs = alloc<sockaddr_storage>()
            val peerLen = fillSockaddr(peerSs.ptr, peer)

            scid.usePinned { pinnedScid ->
                val odcidPtr = odcid?.let { it.usePinned { p -> p.addressOf(0).reinterpret<UByteVar>() } }
                val odcidLen = odcid?.size?.toULong() ?: 0u

                val connPtr = quiche_accept(
                    pinnedScid.addressOf(0).reinterpret(),
                    scid.size.toULong(),
                    odcidPtr,
                    odcidLen,
                    localSs.ptr.reinterpret(),
                    localLen,
                    peerSs.ptr.reinterpret(),
                    peerLen,
                    config.ptr?.reinterpret()
                ) ?: error("Failed to accept QUIC connection")
                KicheConnection(connPtr)
            }
        }
    }

    //region Core I/O

    actual fun recv(buf: ByteArray, len: Int, from: KicheAddress, to: KicheAddress): Int = memScoped {
        val fromSs = alloc<sockaddr_storage>()
        val fromLen = fillSockaddr(fromSs.ptr, from)
        val toSs = alloc<sockaddr_storage>()
        val toLen = fillSockaddr(toSs.ptr, to)

        val ri = alloc<quiche_recv_info>()
        ri.from = fromSs.ptr.reinterpret()
        ri.from_len = fromLen
        ri.to = toSs.ptr.reinterpret()
        ri.to_len = toLen

        buf.usePinned { pinned ->
            val rc = quiche_conn_recv(conn(), pinned.addressOf(0).reinterpret(), len.toULong(), ri.ptr)
            if (rc < 0) KicheException.check(rc.toInt())
            rc.toInt()
        }
    }

    actual fun send(buf: ByteArray, len: Int): KicheSendResult? = memScoped {
        val si = alloc<quiche_send_info>()
        buf.usePinned { pinned ->
            val written = quiche_conn_send(conn(), pinned.addressOf(0).reinterpret(), len.toULong(), si.ptr)
            if (written == QUICHE_ERR_DONE.toLong()) return null
            if (written < 0) return null

            val fromAddr = extractSockaddr(si.from.ptr)
            val toAddr = extractSockaddr(si.to.ptr)
            val atNanos = si.at.tv_sec * 1_000_000_000L + si.at.tv_nsec

            KicheSendResult(written.toInt(), fromAddr, toAddr, atNanos)
        }
    }

    actual fun sendAckEliciting(): Long = quiche_conn_send_ack_eliciting(conn())

    //endregion

    //region Streams

    actual fun streamRecv(streamId: Long, buf: ByteArray, len: Int): KicheStreamRecvResult = memScoped {
        val fin = alloc<BooleanVar>()
        val errorCode = alloc<ULongVar>()
        buf.usePinned { pinned ->
            val rc = quiche_conn_stream_recv(conn(), streamId.toULong(),
                pinned.addressOf(0).reinterpret(), len.toULong(), fin.ptr, errorCode.ptr)
            if (rc < 0) KicheException.check(rc.toInt())
            KicheStreamRecvResult(rc.toInt(), fin.value)
        }
    }

    actual fun streamSend(streamId: Long, buf: ByteArray, len: Int, fin: Boolean): Int = memScoped {
        val errorCode = alloc<ULongVar>()
        buf.usePinned { pinned ->
            val rc = quiche_conn_stream_send(conn(), streamId.toULong(),
                pinned.addressOf(0).reinterpret(), len.toULong(), fin, errorCode.ptr)
            if (rc < 0) KicheException.check(rc.toInt())
            rc.toInt()
        }
    }

    actual fun streamShutdown(streamId: Long, direction: KicheShutdown, err: Long) {
        KicheException.check(quiche_conn_stream_shutdown(conn(), streamId.toULong(),
            direction.value.toUInt(), err.toULong()))
    }

    actual fun streamCapacity(streamId: Long): Int {
        val rc = quiche_conn_stream_capacity(conn(), streamId.toULong())
        if (rc < 0) KicheException.check(rc.toInt())
        return rc.toInt()
    }

    actual fun streamReadableNext(): Long = quiche_conn_stream_readable_next(conn())
    actual fun streamWritableNext(): Long = quiche_conn_stream_writable_next(conn())
    actual fun streamReadable(streamId: Long): Boolean = quiche_conn_stream_readable(conn(), streamId.toULong())

    actual fun streamWritable(streamId: Long, len: Int): Boolean {
        val rc = quiche_conn_stream_writable(conn(), streamId.toULong(), len.toULong())
        if (rc < 0) KicheException.check(rc)
        return rc == 1
    }

    actual fun streamFinished(streamId: Long): Boolean = quiche_conn_stream_finished(conn(), streamId.toULong())

    actual fun streamPriority(streamId: Long, urgency: Int, incremental: Boolean) {
        KicheException.check(quiche_conn_stream_priority(conn(), streamId.toULong(), urgency.toUByte(), incremental))
    }

    //endregion

    //region Datagrams

    actual fun dgramSend(buf: ByteArray, len: Int): Int {
        buf.usePinned { pinned ->
            val rc = quiche_conn_dgram_send(conn(), pinned.addressOf(0).reinterpret(), len.toULong())
            if (rc < 0) KicheException.checkStrict(rc.toInt())
            return rc.toInt()
        }
    }

    actual fun dgramRecv(buf: ByteArray, len: Int): Int {
        buf.usePinned { pinned ->
            val rc = quiche_conn_dgram_recv(conn(), pinned.addressOf(0).reinterpret(), len.toULong())
            if (rc < 0) KicheException.check(rc.toInt())
            return rc.toInt()
        }
    }

    actual fun dgramMaxWritableLen(): Long = quiche_conn_dgram_max_writable_len(conn())
    actual fun dgramRecvFrontLen(): Long = quiche_conn_dgram_recv_front_len(conn())
    actual fun dgramRecvQueueLen(): Long = quiche_conn_dgram_recv_queue_len(conn())
    actual fun dgramRecvQueueByteSize(): Long = quiche_conn_dgram_recv_queue_byte_size(conn())
    actual fun dgramSendQueueLen(): Long = quiche_conn_dgram_send_queue_len(conn())
    actual fun dgramSendQueueByteSize(): Long = quiche_conn_dgram_send_queue_byte_size(conn())
    actual fun isDgramSendQueueFull(): Boolean = quiche_conn_is_dgram_send_queue_full(conn())
    actual fun isDgramRecvQueueFull(): Boolean = quiche_conn_is_dgram_recv_queue_full(conn())

    //endregion

    //region Timer
    actual fun timeoutAsMillis(): Long = quiche_conn_timeout_as_millis(conn()).toLong()
    actual fun timeoutAsNanos(): Long = quiche_conn_timeout_as_nanos(conn()).toLong()
    actual fun onTimeout() = quiche_conn_on_timeout(conn())

    //endregion

    //region State
    actual val isEstablished: Boolean get() = quiche_conn_is_established(conn())
    actual val isClosed: Boolean get() = quiche_conn_is_closed(conn())
    actual val isTimedOut: Boolean get() = quiche_conn_is_timed_out(conn())
    actual val isResumed: Boolean get() = quiche_conn_is_resumed(conn())
    actual val isInEarlyData: Boolean get() = quiche_conn_is_in_early_data(conn())
    actual val isReadable: Boolean get() = quiche_conn_is_readable(conn())
    actual val isDraining: Boolean get() = quiche_conn_is_draining(conn())
    actual val isServer: Boolean get() = quiche_conn_is_server(conn())
    actual fun peerStreamsLeftBidi(): Long = quiche_conn_peer_streams_left_bidi(conn()).toLong()
    actual fun peerStreamsLeftUni(): Long = quiche_conn_peer_streams_left_uni(conn()).toLong()
    actual fun maxSendUdpPayloadSize(): Long = quiche_conn_max_send_udp_payload_size(conn()).toLong()
    actual fun sendQuantum(): Long = quiche_conn_send_quantum(conn()).toLong()

    //endregion

    //region Connection ID management
    actual fun retiredScids(): Long = quiche_conn_retired_scids(conn()).toLong()
    actual fun availableDcids(): Long = quiche_conn_available_dcids(conn()).toLong()
    actual fun scidsLeft(): Long = quiche_conn_scids_left(conn()).toLong()
    actual fun activeScids(): Long = quiche_conn_active_scids(conn()).toLong()

    actual fun newScid(scid: ByteArray, resetToken: ByteArray, retireIfNeeded: Boolean): Long = memScoped {
        val seq = alloc<ULongVar>()
        scid.usePinned { pinnedScid ->
            resetToken.usePinned { pinnedToken ->
                KicheException.check(quiche_conn_new_scid(conn(),
                    pinnedScid.addressOf(0).reinterpret(), scid.size.toULong(),
                    pinnedToken.addressOf(0).reinterpret(), retireIfNeeded, seq.ptr))
            }
        }
        seq.value.toLong()
    }

    actual fun retireDcid(dcidSeq: Long) {
        KicheException.check(quiche_conn_retire_dcid(conn(), dcidSeq.toULong()))
    }

    actual fun retiredScidNext(): ByteArray? = readBytesOut { out, outLen ->
        quiche_conn_retired_scid_next(conn(), out, outLen)
    }
    //endregion

    //region Path migration & multi-path

    actual fun probePath(local: KicheAddress, peer: KicheAddress): Long = memScoped {
        val localSs = alloc<sockaddr_storage>()
        val localLen = fillSockaddr(localSs.ptr, local)
        val peerSs = alloc<sockaddr_storage>()
        val peerLen = fillSockaddr(peerSs.ptr, peer)
        val seq = alloc<ULongVar>()
        val rc = quiche_conn_probe_path(conn(),
            localSs.ptr.reinterpret(), localLen,
            peerSs.ptr.reinterpret(), peerLen, seq.ptr)
        if (rc < 0) KicheException.check(rc)
        seq.value.toLong()
    }

    actual fun isPathValidated(local: KicheAddress, peer: KicheAddress): Boolean = memScoped {
        val localSs = alloc<sockaddr_storage>()
        val localLen = fillSockaddr(localSs.ptr, local)
        val peerSs = alloc<sockaddr_storage>()
        val peerLen = fillSockaddr(peerSs.ptr, peer)
        val rc = quiche_conn_is_path_validated(conn(),
            localSs.ptr.reinterpret(), localLen.toULong(),
            peerSs.ptr.reinterpret(), peerLen.toULong())
        if (rc < 0) KicheException.check(rc)
        rc == 1
    }

    actual fun migrateSource(local: KicheAddress): Long = memScoped {
        val localSs = alloc<sockaddr_storage>()
        val localLen = fillSockaddr(localSs.ptr, local)
        val seq = alloc<ULongVar>()
        val rc = quiche_conn_migrate_source(conn(),
            localSs.ptr.reinterpret(), localLen, seq.ptr)
        if (rc < 0) KicheException.check(rc)
        seq.value.toLong()
    }

    actual fun migrate(local: KicheAddress, peer: KicheAddress): Long = memScoped {
        val localSs = alloc<sockaddr_storage>()
        val localLen = fillSockaddr(localSs.ptr, local)
        val peerSs = alloc<sockaddr_storage>()
        val peerLen = fillSockaddr(peerSs.ptr, peer)
        val seq = alloc<ULongVar>()
        val rc = quiche_conn_migrate(conn(),
            localSs.ptr.reinterpret(), localLen,
            peerSs.ptr.reinterpret(), peerLen, seq.ptr)
        if (rc < 0) KicheException.check(rc)
        seq.value.toLong()
    }

    actual fun pathEventNext(): KichePathEvent? = memScoped {
        val ev = quiche_conn_path_event_next(conn()) ?: return null
        try {
            val type = quiche_path_event_type(ev)
            when (type) {
                QUICHE_PATH_EVENT_REUSED_SOURCE_CONNECTION_ID -> {
                    val id = alloc<ULongVar>()
                    val oldLocalSs = alloc<sockaddr_storage>(); val oldLocalLen = alloc<UIntVar>()
                    val oldPeerSs = alloc<sockaddr_storage>(); val oldPeerLen = alloc<UIntVar>()
                    val localSs = alloc<sockaddr_storage>(); val localLen = alloc<UIntVar>()
                    val peerSs = alloc<sockaddr_storage>(); val peerLen = alloc<UIntVar>()
                    quiche_path_event_reused_source_connection_id(ev, id.ptr,
                        oldLocalSs.ptr, oldLocalLen.ptr, oldPeerSs.ptr, oldPeerLen.ptr,
                        localSs.ptr, localLen.ptr, peerSs.ptr, peerLen.ptr)
                    KichePathEvent.ReusedSourceConnectionId(
                        id = id.value.toLong(),
                        oldLocal = extractSockaddr(oldLocalSs.ptr),
                        oldPeer = extractSockaddr(oldPeerSs.ptr),
                        local = extractSockaddr(localSs.ptr),
                        peer = extractSockaddr(peerSs.ptr),
                    )
                }
                else -> {
                    val localSs = alloc<sockaddr_storage>(); val localLen = alloc<UIntVar>()
                    val peerSs = alloc<sockaddr_storage>(); val peerLen = alloc<UIntVar>()
                    when (type) {
                        QUICHE_PATH_EVENT_NEW ->
                            quiche_path_event_new(ev, localSs.ptr, localLen.ptr, peerSs.ptr, peerLen.ptr)
                        QUICHE_PATH_EVENT_VALIDATED ->
                            quiche_path_event_validated(ev, localSs.ptr, localLen.ptr, peerSs.ptr, peerLen.ptr)
                        QUICHE_PATH_EVENT_FAILED_VALIDATION ->
                            quiche_path_event_failed_validation(ev, localSs.ptr, localLen.ptr, peerSs.ptr, peerLen.ptr)
                        QUICHE_PATH_EVENT_CLOSED ->
                            quiche_path_event_closed(ev, localSs.ptr, localLen.ptr, peerSs.ptr, peerLen.ptr)
                        QUICHE_PATH_EVENT_PEER_MIGRATED ->
                            quiche_path_event_peer_migrated(ev, localSs.ptr, localLen.ptr, peerSs.ptr, peerLen.ptr)
                    }
                    val local = extractSockaddr(localSs.ptr)
                    val peer = extractSockaddr(peerSs.ptr)
                    when (type) {
                        QUICHE_PATH_EVENT_NEW -> KichePathEvent.New(local, peer)
                        QUICHE_PATH_EVENT_VALIDATED -> KichePathEvent.Validated(local, peer)
                        QUICHE_PATH_EVENT_FAILED_VALIDATION -> KichePathEvent.FailedValidation(local, peer)
                        QUICHE_PATH_EVENT_CLOSED -> KichePathEvent.Closed(local, peer)
                        QUICHE_PATH_EVENT_PEER_MIGRATED -> KichePathEvent.PeerMigrated(local, peer)
                    }
                }
            }
        } finally {
            quiche_path_event_free(ev)
        }
    }

    actual fun sendOnPath(buf: ByteArray, len: Int, from: KicheAddress?, to: KicheAddress?): KicheSendResult? = memScoped {
        val si = alloc<quiche_send_info>()
        val fromSs = from?.let { alloc<sockaddr_storage>() }
        val fromLen = from?.let { fillSockaddr(fromSs!!.ptr, it) } ?: 0u
        val toSs = to?.let { alloc<sockaddr_storage>() }
        val toLen = to?.let { fillSockaddr(toSs!!.ptr, it) } ?: 0u

        buf.usePinned { pinned ->
            val written = quiche_conn_send_on_path(conn(),
                pinned.addressOf(0).reinterpret(), len.toULong(),
                fromSs?.ptr?.reinterpret(), fromLen,
                toSs?.ptr?.reinterpret(), toLen,
                si.ptr)
            if (written == QUICHE_ERR_DONE.toLong()) return null
            if (written < 0) { KicheException.check(written.toInt()); return null }

            val fromAddr = extractSockaddr(si.from.ptr)
            val toAddr = extractSockaddr(si.to.ptr)
            val atNanos = si.at.tv_sec * 1_000_000_000L + si.at.tv_nsec
            KicheSendResult(written.toInt(), fromAddr, toAddr, atNanos)
        }
    }

    actual fun sendQuantumOnPath(local: KicheAddress, peer: KicheAddress): Long = memScoped {
        val localSs = alloc<sockaddr_storage>()
        val localLen = fillSockaddr(localSs.ptr, local)
        val peerSs = alloc<sockaddr_storage>()
        val peerLen = fillSockaddr(peerSs.ptr, peer)
        quiche_conn_send_quantum_on_path(conn(),
            localSs.ptr.reinterpret(), localLen,
            peerSs.ptr.reinterpret(), peerLen).toLong()
    }

    actual fun sendAckElicitingOnPath(local: KicheAddress, peer: KicheAddress): Boolean = memScoped {
        val localSs = alloc<sockaddr_storage>()
        val localLen = fillSockaddr(localSs.ptr, local)
        val peerSs = alloc<sockaddr_storage>()
        val peerLen = fillSockaddr(peerSs.ptr, peer)
        val rc = quiche_conn_send_ack_eliciting_on_path(conn(),
            localSs.ptr.reinterpret(), localLen,
            peerSs.ptr.reinterpret(), peerLen)
        if (rc < 0) KicheException.check(rc.toInt())
        rc == 0L
    }

    actual fun pathsIter(from: KicheAddress): List<KicheAddress> = memScoped {
        val fromSs = alloc<sockaddr_storage>()
        val fromLen = fillSockaddr(fromSs.ptr, from)
        val iter = quiche_conn_paths_iter(conn(),
            fromSs.ptr.reinterpret(), fromLen.toULong()) ?: return emptyList()
        try {
            val result = mutableListOf<KicheAddress>()
            val peerSs = alloc<sockaddr_storage>()
            val peerLen = alloc<ULongVar>()
            while (quiche_socket_addr_iter_next(iter, peerSs.ptr, peerLen.ptr)) {
                result.add(extractSockaddr(peerSs.ptr))
            }
            result
        } finally {
            quiche_socket_addr_iter_free(iter)
        }
    }

    //endregion

    //region TLS / session
    actual fun setSession(session: ByteArray) {
        session.usePinned { pinned ->
            KicheException.check(quiche_conn_set_session(conn(),
                pinned.addressOf(0).reinterpret(), session.size.toULong()))
        }
    }

    actual fun session(): ByteArray? = readBytesOut { out, outLen ->
        quiche_conn_session(conn(), out, outLen)
    }

    actual fun setMaxIdleTimeout(v: Long) {
        KicheException.check(quiche_conn_set_max_idle_timeout(conn(), v.toULong()))
    }

    actual fun setKeylogPath(path: String): Boolean = quiche_conn_set_keylog_path(conn(), path)

    //endregion

    //region Close
    actual fun closeConnection(app: Boolean, err: Long, reason: ByteArray) {
        reason.usePinned { pinned ->
            val rc = quiche_conn_close(conn(), app, err.toULong(),
                pinned.addressOf(0).reinterpret(), reason.size.toULong())
            KicheException.checkStrict(rc)
        }
    }

    //endregion

    //region Info
    actual fun applicationProto(): ByteArray? = readBytesOut { out, outLen ->
        quiche_conn_application_proto(conn(), out, outLen)
    }

    actual fun peerCert(): ByteArray? = readBytesOut { out, outLen ->
        quiche_conn_peer_cert(conn(), out, outLen)
    }

    actual fun sourceId(): ByteArray? = readBytesOut { out, outLen ->
        quiche_conn_source_id(conn(), out, outLen)
    }

    actual fun destinationId(): ByteArray? = readBytesOut { out, outLen ->
        quiche_conn_destination_id(conn(), out, outLen)
    }

    actual fun traceId(): ByteArray? = readBytesOut { out, outLen ->
        quiche_conn_trace_id(conn(), out, outLen)
    }

    actual fun serverName(): ByteArray? = readBytesOut { out, outLen ->
        quiche_conn_server_name(conn(), out, outLen)
    }

    actual fun peerError(): KicheConnectionError? = memScoped {
        val isApp = alloc<BooleanVar>()
        val errorCode = alloc<ULongVar>()
        val reason = alloc<CPointerVar<UByteVar>>()
        val reasonLen = alloc<ULongVar>()
        if (!quiche_conn_peer_error(conn(), isApp.ptr, errorCode.ptr, reason.ptr, reasonLen.ptr)) {
            return null
        }
        val reasonBytes = reason.value?.readBytes(reasonLen.value.toInt()) ?: ByteArray(0)
        KicheConnectionError(isApp.value, errorCode.value.toLong(), reasonBytes)
    }

    actual fun localError(): KicheConnectionError? = memScoped {
        val isApp = alloc<BooleanVar>()
        val errorCode = alloc<ULongVar>()
        val reason = alloc<CPointerVar<UByteVar>>()
        val reasonLen = alloc<ULongVar>()
        if (!quiche_conn_local_error(conn(), isApp.ptr, errorCode.ptr, reason.ptr, reasonLen.ptr)) {
            return null
        }
        val reasonBytes = reason.value?.readBytes(reasonLen.value.toInt()) ?: ByteArray(0)
        KicheConnectionError(isApp.value, errorCode.value.toLong(), reasonBytes)
    }

    actual fun stats(): KicheStats = memScoped {
        val s = alloc<quiche_stats>()
        quiche_conn_stats(conn(), s.ptr)
        KicheStats(
            s.recv.toLong(), s.sent.toLong(), s.lost.toLong(), s.spurious_lost.toLong(),
            s.retrans.toLong(), s.sent_bytes.toLong(), s.recv_bytes.toLong(),
            s.acked_bytes.toLong(), s.lost_bytes.toLong(), s.stream_retrans_bytes.toLong(),
            s.dgram_recv.toLong(), s.dgram_sent.toLong(), s.paths_count.toLong(),
            s.reset_stream_count_local.toLong(), s.stopped_stream_count_local.toLong(),
            s.reset_stream_count_remote.toLong(), s.stopped_stream_count_remote.toLong(),
        )
    }

    actual fun pathStats(idx: Long): KichePathStats? = memScoped {
        val ps = alloc<quiche_path_stats>()
        val rc = quiche_conn_path_stats(conn(), idx.toULong(), ps.ptr)
        if (rc != 0) return null
        KichePathStats(
            localAddr = extractSockaddr(ps.local_addr.ptr),
            peerAddr = extractSockaddr(ps.peer_addr.ptr),
            active = ps.active,
            recv = ps.recv.toLong(), sent = ps.sent.toLong(),
            lost = ps.lost.toLong(), retrans = ps.retrans.toLong(),
            rtt = ps.rtt.toLong(), minRtt = ps.min_rtt.toLong(), rttvar = ps.rttvar.toLong(),
            cwnd = ps.cwnd.toLong(), sentBytes = ps.sent_bytes.toLong(),
            recvBytes = ps.recv_bytes.toLong(), lostBytes = ps.lost_bytes.toLong(),
            streamRetransBytes = ps.stream_retrans_bytes.toLong(),
            pmtu = ps.pmtu.toLong(), deliveryRate = ps.delivery_rate.toLong(),
        )
    }

    actual fun peerTransportParams(): KicheTransportParams? = memScoped {
        val tp = alloc<quiche_transport_params>()
        if (!quiche_conn_peer_transport_params(conn(), tp.ptr)) return null
        KicheTransportParams(
            tp.peer_max_idle_timeout.toLong(), tp.peer_max_udp_payload_size.toLong(),
            tp.peer_initial_max_data.toLong(), tp.peer_initial_max_stream_data_bidi_local.toLong(),
            tp.peer_initial_max_stream_data_bidi_remote.toLong(), tp.peer_initial_max_stream_data_uni.toLong(),
            tp.peer_initial_max_streams_bidi.toLong(), tp.peer_initial_max_streams_uni.toLong(),
            tp.peer_ack_delay_exponent.toLong(), tp.peer_max_ack_delay.toLong(),
            tp.peer_disable_active_migration, tp.peer_active_conn_id_limit.toLong(),
            tp.peer_max_datagram_frame_size,
        )
    }

    actual override fun close() {
        ptr?.let {
            quiche_conn_free(it.reinterpret())
            ptr = null
        }
    }

    //endregion

    //region Helpers

    private inline fun readBytesOut(
        block: (CValuesRef<CPointerVar<UByteVar>>, CValuesRef<ULongVar>) -> Unit
    ): ByteArray? = memScoped {
        val out = alloc<CPointerVar<UByteVar>>()
        val outLen = alloc<ULongVar>()
        block(out.ptr, outLen.ptr)
        val len = outLen.value.toInt()
        if (len == 0) return null
        out.value?.readBytes(len)
    }
}

//endregion

//region sockaddr helpers

internal fun MemScope.fillSockaddr(ptr: CPointer<sockaddr_storage>, addr: KicheAddress): UInt {
    val ss = ptr.pointed
    if (addr.ip.size == 4) {
        val sa = ptr.reinterpret<sockaddr_in>().pointed
        sa.sin_family = AF_INET.toUByte()
        sa.sin_port = kiche_htons(addr.port.toUShort())
        addr.ip.usePinned { pinned ->
            memcpy(sa.sin_addr.ptr, pinned.addressOf(0), 4u)
        }
        return sizeOf<sockaddr_in>().toUInt()
    } else {
        val sa6 = ptr.reinterpret<sockaddr_in6>().pointed
        sa6.sin6_family = AF_INET6.toUByte()
        sa6.sin6_port = kiche_htons(addr.port.toUShort())
        addr.ip.usePinned { pinned ->
            memcpy(sa6.sin6_addr.ptr, pinned.addressOf(0), 16u)
        }
        return sizeOf<sockaddr_in6>().toUInt()
    }
}


internal fun extractSockaddr(ptr: CPointer<sockaddr_storage>): KicheAddress {
    val ss = ptr.pointed
    return if (ss.ss_family == AF_INET.toUByte()) {
        val sa = ptr.reinterpret<sockaddr_in>().pointed
        val ip = ByteArray(4)
        ip.usePinned { pinned -> memcpy(pinned.addressOf(0), sa.sin_addr.ptr, 4u) }
        KicheAddress(ip, kiche_ntohs(sa.sin_port).toInt())
    } else {
        val sa6 = ptr.reinterpret<sockaddr_in6>().pointed
        val ip = ByteArray(16)
        ip.usePinned { pinned -> memcpy(pinned.addressOf(0), sa6.sin6_addr.ptr, 16u) }
        KicheAddress(ip, kiche_ntohs(sa6.sin6_port).toInt())
    }
    //endregion
}
