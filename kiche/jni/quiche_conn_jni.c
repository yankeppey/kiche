#include "kiche_jni.h"
#include <string.h>
#include <arpa/inet.h>
#include <netinet/in.h>

#define CONN(handle) ((quiche_conn *)(intptr_t)(handle))

// From helpers.c
extern socklen_t fill_sockaddr(struct sockaddr_storage *ss, const uint8_t *ip, int ip_len, int port);
extern void extract_sockaddr(const struct sockaddr_storage *ss, uint8_t *out_ip, int *out_ip_len, int *out_port);
extern jobject make_kiche_address(JNIEnv *env, const uint8_t *ip, int ip_len, int port);
extern socklen_t fill_sockaddr_from_address(JNIEnv *env, struct sockaddr_storage *ss, jbyteArray ip, jint port);

// --- Lifecycle ---

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeConnect)(JNIEnv *env, jclass clazz,
        jstring serverName, jbyteArray scid,
        jbyteArray localIp, jint localPort,
        jbyteArray peerIp, jint peerPort,
        jlong configHandle) {
    const char *sn = (*env)->GetStringUTFChars(env, serverName, NULL);
    jbyte *scid_buf = (*env)->GetByteArrayElements(env, scid, NULL);
    jsize scid_len = (*env)->GetArrayLength(env, scid);

    struct sockaddr_storage local_ss, peer_ss;
    socklen_t local_len = fill_sockaddr_from_address(env, &local_ss, localIp, localPort);
    socklen_t peer_len = fill_sockaddr_from_address(env, &peer_ss, peerIp, peerPort);

    quiche_conn *conn = quiche_connect(sn,
        (const uint8_t *)scid_buf, (size_t)scid_len,
        (const struct sockaddr *)&local_ss, local_len,
        (const struct sockaddr *)&peer_ss, peer_len,
        (quiche_config *)(intptr_t)configHandle);

    (*env)->ReleaseByteArrayElements(env, scid, scid_buf, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, serverName, sn);
    return (jlong)(intptr_t)conn;
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeAccept)(JNIEnv *env, jclass clazz,
        jbyteArray scid, jbyteArray odcid,
        jbyteArray localIp, jint localPort,
        jbyteArray peerIp, jint peerPort,
        jlong configHandle) {
    jbyte *scid_buf = (*env)->GetByteArrayElements(env, scid, NULL);
    jsize scid_len = (*env)->GetArrayLength(env, scid);

    const uint8_t *odcid_buf = NULL;
    jsize odcid_len = 0;
    if (odcid != NULL) {
        odcid_buf = (const uint8_t *)(*env)->GetByteArrayElements(env, odcid, NULL);
        odcid_len = (*env)->GetArrayLength(env, odcid);
    }

    struct sockaddr_storage local_ss, peer_ss;
    socklen_t local_len = fill_sockaddr_from_address(env, &local_ss, localIp, localPort);
    socklen_t peer_len = fill_sockaddr_from_address(env, &peer_ss, peerIp, peerPort);

    quiche_conn *conn = quiche_accept(
        (const uint8_t *)scid_buf, (size_t)scid_len,
        odcid_buf, (size_t)odcid_len,
        (const struct sockaddr *)&local_ss, local_len,
        (const struct sockaddr *)&peer_ss, peer_len,
        (quiche_config *)(intptr_t)configHandle);

    if (odcid != NULL) {
        (*env)->ReleaseByteArrayElements(env, odcid, (jbyte *)odcid_buf, JNI_ABORT);
    }
    (*env)->ReleaseByteArrayElements(env, scid, scid_buf, JNI_ABORT);
    return (jlong)(intptr_t)conn;
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConnection, nativeFree)(JNIEnv *env, jobject self, jlong handle) {
    quiche_conn_free(CONN(handle));
}

// --- Core I/O ---

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheConnection, nativeRecv)(JNIEnv *env, jobject self, jlong handle,
        jbyteArray buf, jint len, jbyteArray fromIp, jint fromPort, jbyteArray toIp, jint toPort) {
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);

    struct sockaddr_storage from_ss, to_ss;
    socklen_t from_len = fill_sockaddr_from_address(env, &from_ss, fromIp, fromPort);
    socklen_t to_len = fill_sockaddr_from_address(env, &to_ss, toIp, toPort);

    quiche_recv_info ri = {
        .from = (struct sockaddr *)&from_ss,
        .from_len = from_len,
        .to = (struct sockaddr *)&to_ss,
        .to_len = to_len,
    };

    ssize_t rc = quiche_conn_recv(CONN(handle), (uint8_t *)b, (size_t)len, &ri);
    (*env)->ReleaseByteArrayElements(env, buf, b, 0);
    return (jint)rc;
}

// Returns [written, fromIp, fromPort, toIp, toPort, atNanos] encoded in a long array,
// or null if DONE.
JNIEXPORT jlongArray JNICALL
JNI_FN(PKG, KicheConnection, nativeSend)(JNIEnv *env, jobject self, jlong handle,
        jbyteArray buf, jint len) {
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);
    quiche_send_info si;
    ssize_t written = quiche_conn_send(CONN(handle), (uint8_t *)b, (size_t)len, &si);
    (*env)->ReleaseByteArrayElements(env, buf, b, 0);

    if (written == QUICHE_ERR_DONE) return NULL;
    if (written < 0) return NULL; // caller checks

    // Extract addresses from send_info
    uint8_t from_ip[16], to_ip[16];
    int from_ip_len, to_ip_len, from_port, to_port;
    extract_sockaddr(&si.from, from_ip, &from_ip_len, &from_port);
    extract_sockaddr(&si.to, to_ip, &to_ip_len, &to_port);

    // Pack: [written, atNanos, fromIpLen, fromPort, toIpLen, toPort, fromIp..., toIp...]
    // We'll return it differently: written as return value, addresses as output objects.
    // Actually, let's use a simpler approach: return a byte array with all the info packed.
    // For now, return written and set send_info fields via a result object.

    // Build result: long[3] = {written, atNanos, 0} + create address objects
    long at_nanos = (long)si.at.tv_sec * 1000000000L + (long)si.at.tv_nsec;

    // We need to return both the written count and the addresses.
    // Use a helper method to construct KicheSendResult on the Java side.
    // For simplicity, we'll encode everything in a byte array.
    // Actually, let's just store send_info data in instance fields and read them back.

    // Store addresses in thread-local statics and let Kotlin read them.
    // Simplest: return written, and store send_info in the object.

    // Let's take the simplest approach: return a long array with all data packed.
    // [written, atNanos, fromFamily, fromPort, toFamily, toPort]
    // and copy IP bytes into separate byte arrays set on the connection object.

    // Actually, the cleanest JNI approach: return written, pass the send info
    // via output arrays that Kotlin pre-allocates.
    // But for now, let's return the data packed into a result.

    jlongArray result = (*env)->NewLongArray(env, 2);
    jlong vals[2] = { written, at_nanos };
    (*env)->SetLongArrayRegion(env, result, 0, 2, vals);

    // Store from/to addresses in object fields
    static jclass connCls = NULL;
    static jfieldID fFromIp = NULL, fFromPort = NULL, fToIp = NULL, fToPort = NULL;
    if (!connCls) {
        connCls = (*env)->FindClass(env, "eu/buney/kiche/KicheConnection");
        connCls = (jclass)(*env)->NewGlobalRef(env, (jobject)connCls);
        fFromIp = (*env)->GetFieldID(env, connCls, "sendFromIp", "[B");
        fFromPort = (*env)->GetFieldID(env, connCls, "sendFromPort", "I");
        fToIp = (*env)->GetFieldID(env, connCls, "sendToIp", "[B");
        fToPort = (*env)->GetFieldID(env, connCls, "sendToPort", "I");
    }

    jbyteArray fromIpArr = (*env)->NewByteArray(env, from_ip_len);
    (*env)->SetByteArrayRegion(env, fromIpArr, 0, from_ip_len, (const jbyte *)from_ip);
    (*env)->SetObjectField(env, self, fFromIp, fromIpArr);
    (*env)->SetIntField(env, self, fFromPort, from_port);

    jbyteArray toIpArr = (*env)->NewByteArray(env, to_ip_len);
    (*env)->SetByteArrayRegion(env, toIpArr, 0, to_ip_len, (const jbyte *)to_ip);
    (*env)->SetObjectField(env, self, fToIp, toIpArr);
    (*env)->SetIntField(env, self, fToPort, to_port);

    return result;
}

// --- Streams ---

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeStreamRecv)(JNIEnv *env, jobject self, jlong handle,
        jlong streamId, jbyteArray buf, jint len) {
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);
    bool fin = false;
    uint64_t error_code = 0;
    ssize_t rc = quiche_conn_stream_recv(CONN(handle), (uint64_t)streamId,
        (uint8_t *)b, (size_t)len, &fin, &error_code);
    (*env)->ReleaseByteArrayElements(env, buf, b, 0);
    // Pack: high 32 bits = read count (or error), low bit of high byte = fin flag
    // Actually simpler: return read count, store fin in field
    static jfieldID fFin = NULL;
    if (!fFin) {
        jclass cls = (*env)->GetObjectClass(env, self);
        fFin = (*env)->GetFieldID(env, cls, "lastStreamRecvFin", "Z");
    }
    (*env)->SetBooleanField(env, self, fFin, fin);
    return (jlong)rc;
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeStreamSend)(JNIEnv *env, jobject self, jlong handle,
        jlong streamId, jbyteArray buf, jint len, jboolean fin) {
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);
    uint64_t error_code = 0;
    ssize_t rc = quiche_conn_stream_send(CONN(handle), (uint64_t)streamId,
        (const uint8_t *)b, (size_t)len, fin, &error_code);
    (*env)->ReleaseByteArrayElements(env, buf, b, JNI_ABORT);
    return (jlong)rc;
}

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheConnection, nativeStreamShutdown)(JNIEnv *env, jobject self, jlong handle,
        jlong streamId, jint direction, jlong err) {
    return quiche_conn_stream_shutdown(CONN(handle), (uint64_t)streamId,
        (enum quiche_shutdown)direction, (uint64_t)err);
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeStreamCapacity)(JNIEnv *env, jobject self, jlong handle, jlong streamId) {
    return (jlong)quiche_conn_stream_capacity(CONN(handle), (uint64_t)streamId);
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeStreamReadableNext)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_stream_readable_next(CONN(handle));
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeStreamWritableNext)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_stream_writable_next(CONN(handle));
}

JNIEXPORT jboolean JNICALL
JNI_FN(PKG, KicheConnection, nativeStreamReadable)(JNIEnv *env, jobject self, jlong handle, jlong streamId) {
    return quiche_conn_stream_readable(CONN(handle), (uint64_t)streamId);
}

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheConnection, nativeStreamWritable)(JNIEnv *env, jobject self, jlong handle, jlong streamId, jint len) {
    return quiche_conn_stream_writable(CONN(handle), (uint64_t)streamId, (size_t)len);
}

JNIEXPORT jboolean JNICALL
JNI_FN(PKG, KicheConnection, nativeStreamFinished)(JNIEnv *env, jobject self, jlong handle, jlong streamId) {
    return quiche_conn_stream_finished(CONN(handle), (uint64_t)streamId);
}

// --- Datagrams ---

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeDgramSend)(JNIEnv *env, jobject self, jlong handle,
        jbyteArray buf, jint len) {
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);
    ssize_t rc = quiche_conn_dgram_send(CONN(handle), (const uint8_t *)b, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, buf, b, JNI_ABORT);
    return (jlong)rc;
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeDgramRecv)(JNIEnv *env, jobject self, jlong handle,
        jbyteArray buf, jint len) {
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);
    ssize_t rc = quiche_conn_dgram_recv(CONN(handle), (uint8_t *)b, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, buf, b, 0);
    return (jlong)rc;
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeDgramMaxWritableLen)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_dgram_max_writable_len(CONN(handle));
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeDgramRecvQueueLen)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_dgram_recv_queue_len(CONN(handle));
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeDgramSendQueueLen)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_dgram_send_queue_len(CONN(handle));
}

JNIEXPORT jboolean JNICALL
JNI_FN(PKG, KicheConnection, nativeIsDgramSendQueueFull)(JNIEnv *env, jobject self, jlong handle) {
    return quiche_conn_is_dgram_send_queue_full(CONN(handle));
}

JNIEXPORT jboolean JNICALL
JNI_FN(PKG, KicheConnection, nativeIsDgramRecvQueueFull)(JNIEnv *env, jobject self, jlong handle) {
    return quiche_conn_is_dgram_recv_queue_full(CONN(handle));
}

// --- Timer ---

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeTimeoutAsMillis)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_timeout_as_millis(CONN(handle));
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeTimeoutAsNanos)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_timeout_as_nanos(CONN(handle));
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConnection, nativeOnTimeout)(JNIEnv *env, jobject self, jlong handle) {
    quiche_conn_on_timeout(CONN(handle));
}

// --- State queries ---

JNIEXPORT jboolean JNICALL
JNI_FN(PKG, KicheConnection, nativeIsEstablished)(JNIEnv *env, jobject self, jlong handle) {
    return quiche_conn_is_established(CONN(handle));
}

JNIEXPORT jboolean JNICALL
JNI_FN(PKG, KicheConnection, nativeIsClosed)(JNIEnv *env, jobject self, jlong handle) {
    return quiche_conn_is_closed(CONN(handle));
}

JNIEXPORT jboolean JNICALL
JNI_FN(PKG, KicheConnection, nativeIsTimedOut)(JNIEnv *env, jobject self, jlong handle) {
    return quiche_conn_is_timed_out(CONN(handle));
}

JNIEXPORT jboolean JNICALL
JNI_FN(PKG, KicheConnection, nativeIsResumed)(JNIEnv *env, jobject self, jlong handle) {
    return quiche_conn_is_resumed(CONN(handle));
}

JNIEXPORT jboolean JNICALL
JNI_FN(PKG, KicheConnection, nativeIsInEarlyData)(JNIEnv *env, jobject self, jlong handle) {
    return quiche_conn_is_in_early_data(CONN(handle));
}

JNIEXPORT jboolean JNICALL
JNI_FN(PKG, KicheConnection, nativeIsReadable)(JNIEnv *env, jobject self, jlong handle) {
    return quiche_conn_is_readable(CONN(handle));
}

JNIEXPORT jboolean JNICALL
JNI_FN(PKG, KicheConnection, nativeIsDraining)(JNIEnv *env, jobject self, jlong handle) {
    return quiche_conn_is_draining(CONN(handle));
}

JNIEXPORT jboolean JNICALL
JNI_FN(PKG, KicheConnection, nativeIsServer)(JNIEnv *env, jobject self, jlong handle) {
    return quiche_conn_is_server(CONN(handle));
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativePeerStreamsLeftBidi)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_peer_streams_left_bidi(CONN(handle));
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativePeerStreamsLeftUni)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_peer_streams_left_uni(CONN(handle));
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeMaxSendUdpPayloadSize)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_max_send_udp_payload_size(CONN(handle));
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeSendQuantum)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_send_quantum(CONN(handle));
}

// --- Close ---

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheConnection, nativeClose)(JNIEnv *env, jobject self, jlong handle,
        jboolean app, jlong err, jbyteArray reason) {
    jbyte *r = (*env)->GetByteArrayElements(env, reason, NULL);
    jsize r_len = (*env)->GetArrayLength(env, reason);
    int rc = quiche_conn_close(CONN(handle), app, (uint64_t)err,
        (const uint8_t *)r, (size_t)r_len);
    (*env)->ReleaseByteArrayElements(env, reason, r, JNI_ABORT);
    return rc;
}

// --- Info (byte array outputs) ---

JNIEXPORT jbyteArray JNICALL
JNI_FN(PKG, KicheConnection, nativeApplicationProto)(JNIEnv *env, jobject self, jlong handle) {
    const uint8_t *out;
    size_t out_len;
    quiche_conn_application_proto(CONN(handle), &out, &out_len);
    if (out_len == 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)out_len);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)out_len, (const jbyte *)out);
    return result;
}

JNIEXPORT jbyteArray JNICALL
JNI_FN(PKG, KicheConnection, nativePeerCert)(JNIEnv *env, jobject self, jlong handle) {
    const uint8_t *out;
    size_t out_len;
    quiche_conn_peer_cert(CONN(handle), &out, &out_len);
    if (out_len == 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)out_len);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)out_len, (const jbyte *)out);
    return result;
}

JNIEXPORT jbyteArray JNICALL
JNI_FN(PKG, KicheConnection, nativeSourceId)(JNIEnv *env, jobject self, jlong handle) {
    const uint8_t *out;
    size_t out_len;
    quiche_conn_source_id(CONN(handle), &out, &out_len);
    if (out_len == 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)out_len);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)out_len, (const jbyte *)out);
    return result;
}

JNIEXPORT jbyteArray JNICALL
JNI_FN(PKG, KicheConnection, nativeDestinationId)(JNIEnv *env, jobject self, jlong handle) {
    const uint8_t *out;
    size_t out_len;
    quiche_conn_destination_id(CONN(handle), &out, &out_len);
    if (out_len == 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)out_len);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)out_len, (const jbyte *)out);
    return result;
}

// --- Peer/Local error ---

// Returns [isApp, errorCode, reasonLen] or null if no error.
// Reason bytes stored in field.
JNIEXPORT jlongArray JNICALL
JNI_FN(PKG, KicheConnection, nativePeerError)(JNIEnv *env, jobject self, jlong handle) {
    bool is_app;
    uint64_t error_code;
    const uint8_t *reason;
    size_t reason_len;
    if (!quiche_conn_peer_error(CONN(handle), &is_app, &error_code, &reason, &reason_len)) {
        return NULL;
    }
    jlongArray result = (*env)->NewLongArray(env, 3);
    jlong vals[3] = { is_app ? 1 : 0, (jlong)error_code, (jlong)reason_len };
    (*env)->SetLongArrayRegion(env, result, 0, 3, vals);

    if (reason_len > 0) {
        static jfieldID fReason = NULL;
        if (!fReason) {
            jclass cls = (*env)->GetObjectClass(env, self);
            fReason = (*env)->GetFieldID(env, cls, "lastErrorReason", "[B");
        }
        jbyteArray reasonArr = (*env)->NewByteArray(env, (jsize)reason_len);
        (*env)->SetByteArrayRegion(env, reasonArr, 0, (jsize)reason_len, (const jbyte *)reason);
        (*env)->SetObjectField(env, self, fReason, reasonArr);
    }
    return result;
}

JNIEXPORT jlongArray JNICALL
JNI_FN(PKG, KicheConnection, nativeLocalError)(JNIEnv *env, jobject self, jlong handle) {
    bool is_app;
    uint64_t error_code;
    const uint8_t *reason;
    size_t reason_len;
    if (!quiche_conn_local_error(CONN(handle), &is_app, &error_code, &reason, &reason_len)) {
        return NULL;
    }
    jlongArray result = (*env)->NewLongArray(env, 3);
    jlong vals[3] = { is_app ? 1 : 0, (jlong)error_code, (jlong)reason_len };
    (*env)->SetLongArrayRegion(env, result, 0, 3, vals);

    if (reason_len > 0) {
        static jfieldID fReason = NULL;
        if (!fReason) {
            jclass cls = (*env)->GetObjectClass(env, self);
            fReason = (*env)->GetFieldID(env, cls, "lastErrorReason", "[B");
        }
        jbyteArray reasonArr = (*env)->NewByteArray(env, (jsize)reason_len);
        (*env)->SetByteArrayRegion(env, reasonArr, 0, (jsize)reason_len, (const jbyte *)reason);
        (*env)->SetObjectField(env, self, fReason, reasonArr);
    }
    return result;
}

// --- Stats ---

JNIEXPORT jlongArray JNICALL
JNI_FN(PKG, KicheConnection, nativeStats)(JNIEnv *env, jobject self, jlong handle) {
    quiche_stats stats;
    quiche_conn_stats(CONN(handle), &stats);
    // Pack 17 fields into a long array
    jlongArray result = (*env)->NewLongArray(env, 17);
    jlong vals[17] = {
        (jlong)stats.recv, (jlong)stats.sent, (jlong)stats.lost,
        (jlong)stats.spurious_lost, (jlong)stats.retrans,
        (jlong)stats.sent_bytes, (jlong)stats.recv_bytes,
        (jlong)stats.acked_bytes, (jlong)stats.lost_bytes,
        (jlong)stats.stream_retrans_bytes,
        (jlong)stats.dgram_recv, (jlong)stats.dgram_sent,
        (jlong)stats.paths_count,
        (jlong)stats.reset_stream_count_local, (jlong)stats.stopped_stream_count_local,
        (jlong)stats.reset_stream_count_remote, (jlong)stats.stopped_stream_count_remote,
    };
    (*env)->SetLongArrayRegion(env, result, 0, 17, vals);
    return result;
}

// --- Transport params ---

JNIEXPORT jlongArray JNICALL
JNI_FN(PKG, KicheConnection, nativePeerTransportParams)(JNIEnv *env, jobject self, jlong handle) {
    quiche_transport_params tp;
    if (!quiche_conn_peer_transport_params(CONN(handle), &tp)) {
        return NULL;
    }
    jlongArray result = (*env)->NewLongArray(env, 13);
    jlong vals[13] = {
        (jlong)tp.peer_max_idle_timeout,
        (jlong)tp.peer_max_udp_payload_size,
        (jlong)tp.peer_initial_max_data,
        (jlong)tp.peer_initial_max_stream_data_bidi_local,
        (jlong)tp.peer_initial_max_stream_data_bidi_remote,
        (jlong)tp.peer_initial_max_stream_data_uni,
        (jlong)tp.peer_initial_max_streams_bidi,
        (jlong)tp.peer_initial_max_streams_uni,
        (jlong)tp.peer_ack_delay_exponent,
        (jlong)tp.peer_max_ack_delay,
        tp.peer_disable_active_migration ? 1L : 0L,
        (jlong)tp.peer_active_conn_id_limit,
        (jlong)tp.peer_max_datagram_frame_size,
    };
    (*env)->SetLongArrayRegion(env, result, 0, 13, vals);
    return result;
}
