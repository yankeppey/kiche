@file:OptIn(ExperimentalForeignApi::class)

package eu.buney.kiche

import kotlinx.cinterop.*
import quiche.c.*

actual class KicheH3Connection actual constructor(
    quicConn: KicheConnection,
    config: KicheH3Config
) : AutoCloseable {
    private var ptr: COpaquePointer? = quiche_h3_conn_new_with_transport(
        quicConn.ptr?.reinterpret(), config.ptr?.reinterpret()
    ) ?: error("Failed to create HTTP/3 connection")

    private fun h3(): CPointer<cnames.structs.quiche_h3_conn> =
        (ptr ?: error("KicheH3Connection is closed")).reinterpret()

    actual fun poll(quicConn: KicheConnection): KicheH3Event? = memScoped {
        val ev = alloc<CPointerVar<cnames.structs.quiche_h3_event>>()
        val streamId = quiche_h3_conn_poll(h3(), quicConn.ptr!!.reinterpret(), ev.ptr)
        if (streamId < 0) return null

        val evPtr = ev.value ?: return null
        @Suppress("USELESS_CAST")
        val rawType = quiche_h3_event_type(evPtr) as UInt
        val eventType = KicheH3EventType.fromValue(rawType.toInt()) ?: run {
            quiche_h3_event_free(evPtr)
            return null
        }

        val headers = if (eventType == KicheH3EventType.Headers) {
            val collected = mutableListOf<KicheH3Header>()
            val stableRef = StableRef.create(collected)
            quiche_h3_event_for_each_header(evPtr, staticCFunction { name, nameLen, value, valueLen, argp ->
                val list = argp!!.asStableRef<MutableList<KicheH3Header>>().get()
                val n = name!!.readBytes(nameLen.toInt())
                val v = value!!.readBytes(valueLen.toInt())
                list.add(KicheH3Header(n, v))
                0
            }, stableRef.asCPointer())
            stableRef.dispose()
            collected
        } else null

        quiche_h3_event_free(evPtr)
        KicheH3Event(eventType, streamId, headers)
    }

    actual fun sendRequest(
        quicConn: KicheConnection,
        headers: List<KicheH3Header>,
        fin: Boolean
    ): Long = memScoped {
        val hdrs = allocArray<quiche_h3_header>(headers.size)
        // Pin all byte arrays for the duration
        val pins = headers.map { h ->
            h.name.pin() to h.value.pin()
        }
        for (i in headers.indices) {
            hdrs[i].name = pins[i].first.addressOf(0).reinterpret()
            hdrs[i].name_len = headers[i].name.size.toULong()
            hdrs[i].value = pins[i].second.addressOf(0).reinterpret()
            hdrs[i].value_len = headers[i].value.size.toULong()
        }
        val rc = quiche_h3_send_request(h3(), quicConn.ptr!!.reinterpret(),
            hdrs, headers.size.toULong(), fin)
        pins.forEach { (n, v) -> n.unpin(); v.unpin() }
        if (rc < 0) KicheException.check(rc.toInt())
        rc
    }

    actual fun sendBody(quicConn: KicheConnection, streamId: Long, body: ByteArray, fin: Boolean): Int {
        body.usePinned { pinned ->
            val rc = quiche_h3_send_body(h3(), quicConn.ptr!!.reinterpret(),
                streamId.toULong(), pinned.addressOf(0).reinterpret(), body.size.toULong(), fin)
            if (rc < 0) KicheException.check(rc.toInt())
            return rc.toInt()
        }
    }

    actual fun recvBody(quicConn: KicheConnection, streamId: Long, buf: ByteArray): Int {
        buf.usePinned { pinned ->
            val rc = quiche_h3_recv_body(h3(), quicConn.ptr!!.reinterpret(),
                streamId.toULong(), pinned.addressOf(0).reinterpret(), buf.size.toULong())
            if (rc < 0) KicheException.check(rc.toInt())
            return rc.toInt()
        }
    }

    actual fun sendResponse(
        quicConn: KicheConnection,
        streamId: Long,
        headers: List<KicheH3Header>,
        fin: Boolean
    ) = memScoped {
        val hdrs = allocArray<quiche_h3_header>(headers.size)
        val pins = headers.map { h -> h.name.pin() to h.value.pin() }
        for (i in headers.indices) {
            hdrs[i].name = pins[i].first.addressOf(0).reinterpret()
            hdrs[i].name_len = headers[i].name.size.toULong()
            hdrs[i].value = pins[i].second.addressOf(0).reinterpret()
            hdrs[i].value_len = headers[i].value.size.toULong()
        }
        val rc = quiche_h3_send_response(h3(), quicConn.ptr!!.reinterpret(),
            streamId.toULong(), hdrs, headers.size.toULong(), fin)
        pins.forEach { (n, v) -> n.unpin(); v.unpin() }
        KicheException.check(rc)
    }

    actual fun sendGoaway(quicConn: KicheConnection, id: Long) {
        KicheException.check(quiche_h3_send_goaway(h3(), quicConn.ptr!!.reinterpret(), id.toULong()))
    }

    actual fun dgramEnabledByPeer(quicConn: KicheConnection): Boolean =
        quiche_h3_dgram_enabled_by_peer(h3(), quicConn.ptr!!.reinterpret())

    actual fun stats(): KicheH3Stats = memScoped {
        val s = alloc<quiche_h3_stats>()
        quiche_h3_conn_stats(h3(), s.ptr)
        KicheH3Stats(s.qpack_encoder_stream_recv_bytes.toLong(), s.qpack_decoder_stream_recv_bytes.toLong())
    }

    actual override fun close() {
        ptr?.let {
            quiche_h3_conn_free(it.reinterpret())
            ptr = null
        }
    }
}
