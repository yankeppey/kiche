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

// Returns a KicheSendResult Java object, or null if DONE.
JNIEXPORT jobject JNICALL
JNI_FN(PKG, KicheConnection, nativeSend)(JNIEnv *env, jobject self, jlong handle,
        jbyteArray buf, jint len) {
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);
    quiche_send_info si;
    ssize_t written = quiche_conn_send(CONN(handle), (uint8_t *)b, (size_t)len, &si);
    (*env)->ReleaseByteArrayElements(env, buf, b, 0);

    if (written == QUICHE_ERR_DONE) return NULL;
    if (written < 0) { throw_kiche_exception(env, (int)written); return NULL; }

    return make_send_result(env, (int)written, &si);
}

// --- Streams ---

// Returns a KicheStreamRecvResult, or throws KicheException on error.
JNIEXPORT jobject JNICALL
JNI_FN(PKG, KicheConnection, nativeStreamRecv)(JNIEnv *env, jobject self, jlong handle,
        jlong streamId, jbyteArray buf, jint len) {
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);
    bool fin = false;
    uint64_t error_code = 0;
    ssize_t rc = quiche_conn_stream_recv(CONN(handle), (uint64_t)streamId,
        (uint8_t *)b, (size_t)len, &fin, &error_code);
    (*env)->ReleaseByteArrayElements(env, buf, b, 0);
    if (rc < 0) { throw_kiche_exception(env, (int)rc); return NULL; }

    static jclass cls = NULL;
    static jmethodID ctor = NULL;
    if (!cls) {
        cls = (*env)->FindClass(env, "eu/buney/kiche/KicheStreamRecvResult");
        cls = (jclass)(*env)->NewGlobalRef(env, (jobject)cls);
        ctor = (*env)->GetMethodID(env, cls, "<init>", "(IZ)V");
    }
    return (*env)->NewObject(env, cls, ctor, (jint)rc, (jboolean)fin);
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

// --- Send ACK eliciting ---

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeSendAckEliciting)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_send_ack_eliciting(CONN(handle));
}

// --- Stream priority ---

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheConnection, nativeStreamPriority)(JNIEnv *env, jobject self, jlong handle,
        jlong streamId, jint urgency, jboolean incremental) {
    return quiche_conn_stream_priority(CONN(handle), (uint64_t)streamId,
        (uint8_t)urgency, incremental);
}

// --- Datagram extras ---

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeDgramRecvFrontLen)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_dgram_recv_front_len(CONN(handle));
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeDgramRecvQueueByteSize)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_dgram_recv_queue_byte_size(CONN(handle));
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeDgramSendQueueByteSize)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_dgram_send_queue_byte_size(CONN(handle));
}

// --- Connection ID management ---

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeRetiredScids)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_retired_scids(CONN(handle));
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeAvailableDcids)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_available_dcids(CONN(handle));
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeScidsLeft)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_scids_left(CONN(handle));
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeActiveScids)(JNIEnv *env, jobject self, jlong handle) {
    return (jlong)quiche_conn_active_scids(CONN(handle));
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeNewScid)(JNIEnv *env, jobject self, jlong handle,
        jbyteArray scid, jbyteArray resetToken, jboolean retireIfNeeded) {
    jbyte *scid_buf = (*env)->GetByteArrayElements(env, scid, NULL);
    jsize scid_len = (*env)->GetArrayLength(env, scid);
    jbyte *token_buf = (*env)->GetByteArrayElements(env, resetToken, NULL);
    uint64_t seq = 0;
    int rc = quiche_conn_new_scid(CONN(handle),
        (const uint8_t *)scid_buf, (size_t)scid_len,
        (const uint8_t *)token_buf, retireIfNeeded, &seq);
    (*env)->ReleaseByteArrayElements(env, resetToken, token_buf, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, scid, scid_buf, JNI_ABORT);
    if (rc < 0) return (jlong)rc; // error code
    return (jlong)seq;
}

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheConnection, nativeRetireDcid)(JNIEnv *env, jobject self, jlong handle, jlong dcidSeq) {
    return quiche_conn_retire_dcid(CONN(handle), (uint64_t)dcidSeq);
}

JNIEXPORT jbyteArray JNICALL
JNI_FN(PKG, KicheConnection, nativeRetiredScidNext)(JNIEnv *env, jobject self, jlong handle) {
    const uint8_t *out;
    size_t out_len;
    if (!quiche_conn_retired_scid_next(CONN(handle), &out, &out_len)) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)out_len);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)out_len, (const jbyte *)out);
    return result;
}

// --- TLS / session ---

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheConnection, nativeSetSession)(JNIEnv *env, jobject self, jlong handle, jbyteArray session) {
    jbyte *buf = (*env)->GetByteArrayElements(env, session, NULL);
    jsize len = (*env)->GetArrayLength(env, session);
    int rc = quiche_conn_set_session(CONN(handle), (const uint8_t *)buf, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, session, buf, JNI_ABORT);
    return rc;
}

JNIEXPORT jbyteArray JNICALL
JNI_FN(PKG, KicheConnection, nativeSession)(JNIEnv *env, jobject self, jlong handle) {
    const uint8_t *out;
    size_t out_len;
    quiche_conn_session(CONN(handle), &out, &out_len);
    if (out_len == 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)out_len);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)out_len, (const jbyte *)out);
    return result;
}

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheConnection, nativeSetMaxIdleTimeout)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    return quiche_conn_set_max_idle_timeout(CONN(handle), (uint64_t)v);
}

JNIEXPORT jboolean JNICALL
JNI_FN(PKG, KicheConnection, nativeSetKeylogPath)(JNIEnv *env, jobject self, jlong handle, jstring path) {
    const char *c_path = (*env)->GetStringUTFChars(env, path, NULL);
    bool rc = quiche_conn_set_keylog_path(CONN(handle), c_path);
    (*env)->ReleaseStringUTFChars(env, path, c_path);
    return rc;
}

// --- Info (byte array outputs) ---

JNIEXPORT jbyteArray JNICALL
JNI_FN(PKG, KicheConnection, nativeTraceId)(JNIEnv *env, jobject self, jlong handle) {
    const uint8_t *out;
    size_t out_len;
    quiche_conn_trace_id(CONN(handle), &out, &out_len);
    if (out_len == 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)out_len);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)out_len, (const jbyte *)out);
    return result;
}

JNIEXPORT jbyteArray JNICALL
JNI_FN(PKG, KicheConnection, nativeServerName)(JNIEnv *env, jobject self, jlong handle) {
    const uint8_t *out;
    size_t out_len;
    quiche_conn_server_name(CONN(handle), &out, &out_len);
    if (out_len == 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)out_len);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)out_len, (const jbyte *)out);
    return result;
}

// --- Info (byte array outputs, continued) ---

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

static jobject make_connection_error(JNIEnv *env, bool is_app, uint64_t error_code,
                                     const uint8_t *reason, size_t reason_len) {
    static jclass cls = NULL;
    static jmethodID ctor = NULL;
    if (!cls) {
        cls = (*env)->FindClass(env, "eu/buney/kiche/KicheConnectionError");
        cls = (jclass)(*env)->NewGlobalRef(env, (jobject)cls);
        ctor = (*env)->GetMethodID(env, cls, "<init>", "(ZJ[B)V");
    }
    jbyteArray reasonArr = (*env)->NewByteArray(env, (jsize)reason_len);
    if (reason_len > 0) {
        (*env)->SetByteArrayRegion(env, reasonArr, 0, (jsize)reason_len, (const jbyte *)reason);
    }
    return (*env)->NewObject(env, cls, ctor, (jboolean)is_app, (jlong)error_code, reasonArr);
}

JNIEXPORT jobject JNICALL
JNI_FN(PKG, KicheConnection, nativePeerError)(JNIEnv *env, jobject self, jlong handle) {
    bool is_app;
    uint64_t error_code;
    const uint8_t *reason;
    size_t reason_len;
    if (!quiche_conn_peer_error(CONN(handle), &is_app, &error_code, &reason, &reason_len)) {
        return NULL;
    }
    return make_connection_error(env, is_app, error_code, reason, reason_len);
}

JNIEXPORT jobject JNICALL
JNI_FN(PKG, KicheConnection, nativeLocalError)(JNIEnv *env, jobject self, jlong handle) {
    bool is_app;
    uint64_t error_code;
    const uint8_t *reason;
    size_t reason_len;
    if (!quiche_conn_local_error(CONN(handle), &is_app, &error_code, &reason, &reason_len)) {
        return NULL;
    }
    return make_connection_error(env, is_app, error_code, reason, reason_len);
}

// --- Stats ---

JNIEXPORT jobject JNICALL
JNI_FN(PKG, KicheConnection, nativeStats)(JNIEnv *env, jobject self, jlong handle) {
    static jclass cls = NULL;
    static jmethodID ctor = NULL;
    if (!cls) {
        cls = (*env)->FindClass(env, "eu/buney/kiche/KicheStats");
        cls = (jclass)(*env)->NewGlobalRef(env, (jobject)cls);
        ctor = (*env)->GetMethodID(env, cls, "<init>", "(JJJJJJJJJJJJJJJJJ)V");
    }
    quiche_stats s;
    quiche_conn_stats(CONN(handle), &s);
    return (*env)->NewObject(env, cls, ctor,
        (jlong)s.recv, (jlong)s.sent, (jlong)s.lost, (jlong)s.spurious_lost, (jlong)s.retrans,
        (jlong)s.sent_bytes, (jlong)s.recv_bytes, (jlong)s.acked_bytes, (jlong)s.lost_bytes,
        (jlong)s.stream_retrans_bytes,
        (jlong)s.dgram_recv, (jlong)s.dgram_sent, (jlong)s.paths_count,
        (jlong)s.reset_stream_count_local, (jlong)s.stopped_stream_count_local,
        (jlong)s.reset_stream_count_remote, (jlong)s.stopped_stream_count_remote);
}

// --- Path stats ---

JNIEXPORT jobject JNICALL
JNI_FN(PKG, KicheConnection, nativePathStats)(JNIEnv *env, jobject self, jlong handle, jlong idx) {
    static jclass cls = NULL;
    static jmethodID ctor = NULL;
    if (!cls) {
        cls = (*env)->FindClass(env, "eu/buney/kiche/KichePathStats");
        cls = (jclass)(*env)->NewGlobalRef(env, (jobject)cls);
        ctor = (*env)->GetMethodID(env, cls, "<init>",
            "(Leu/buney/kiche/KicheAddress;Leu/buney/kiche/KicheAddress;"
            "ZJJJJJJJJJJJJJJ)V");
    }
    quiche_path_stats ps;
    int rc = quiche_conn_path_stats(CONN(handle), (size_t)idx, &ps);
    if (rc != 0) return NULL;

    uint8_t ip_buf[16]; int ip_len, port;
    extract_sockaddr(&ps.local_addr, ip_buf, &ip_len, &port);
    jobject localAddr = make_kiche_address(env, ip_buf, ip_len, port);
    extract_sockaddr(&ps.peer_addr, ip_buf, &ip_len, &port);
    jobject peerAddr = make_kiche_address(env, ip_buf, ip_len, port);

    jobject result = (*env)->NewObject(env, cls, ctor,
        localAddr, peerAddr, (jboolean)ps.active,
        (jlong)ps.recv, (jlong)ps.sent, (jlong)ps.lost, (jlong)ps.retrans,
        (jlong)ps.rtt, (jlong)ps.min_rtt, (jlong)ps.rttvar, (jlong)ps.cwnd,
        (jlong)ps.sent_bytes, (jlong)ps.recv_bytes, (jlong)ps.lost_bytes,
        (jlong)ps.stream_retrans_bytes, (jlong)ps.pmtu, (jlong)ps.delivery_rate);
    (*env)->DeleteLocalRef(env, localAddr);
    (*env)->DeleteLocalRef(env, peerAddr);
    return result;
}

// --- Transport params ---

JNIEXPORT jobject JNICALL
JNI_FN(PKG, KicheConnection, nativePeerTransportParams)(JNIEnv *env, jobject self, jlong handle) {
    static jclass cls = NULL;
    static jmethodID ctor = NULL;
    if (!cls) {
        cls = (*env)->FindClass(env, "eu/buney/kiche/KicheTransportParams");
        cls = (jclass)(*env)->NewGlobalRef(env, (jobject)cls);
        ctor = (*env)->GetMethodID(env, cls, "<init>", "(JJJJJJJJJJZJJ)V");
    }
    quiche_transport_params tp;
    if (!quiche_conn_peer_transport_params(CONN(handle), &tp)) {
        return NULL;
    }
    return (*env)->NewObject(env, cls, ctor,
        (jlong)tp.peer_max_idle_timeout, (jlong)tp.peer_max_udp_payload_size,
        (jlong)tp.peer_initial_max_data,
        (jlong)tp.peer_initial_max_stream_data_bidi_local,
        (jlong)tp.peer_initial_max_stream_data_bidi_remote,
        (jlong)tp.peer_initial_max_stream_data_uni,
        (jlong)tp.peer_initial_max_streams_bidi, (jlong)tp.peer_initial_max_streams_uni,
        (jlong)tp.peer_ack_delay_exponent, (jlong)tp.peer_max_ack_delay,
        (jboolean)tp.peer_disable_active_migration,
        (jlong)tp.peer_active_conn_id_limit, (jlong)tp.peer_max_datagram_frame_size);
}

// --- Path migration & multi-path ---

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeProbePath)(JNIEnv *env, jobject self, jlong handle,
        jbyteArray localIp, jint localPort, jbyteArray peerIp, jint peerPort) {
    struct sockaddr_storage local_ss, peer_ss;
    socklen_t local_len = fill_sockaddr_from_address(env, &local_ss, localIp, localPort);
    socklen_t peer_len = fill_sockaddr_from_address(env, &peer_ss, peerIp, peerPort);
    uint64_t seq = 0;
    int rc = quiche_conn_probe_path(CONN(handle),
        (const struct sockaddr *)&local_ss, local_len,
        (const struct sockaddr *)&peer_ss, peer_len, &seq);
    if (rc < 0) return (jlong)rc;
    return (jlong)seq;
}

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheConnection, nativeIsPathValidated)(JNIEnv *env, jobject self, jlong handle,
        jbyteArray localIp, jint localPort, jbyteArray peerIp, jint peerPort) {
    struct sockaddr_storage local_ss, peer_ss;
    socklen_t local_len = fill_sockaddr_from_address(env, &local_ss, localIp, localPort);
    socklen_t peer_len = fill_sockaddr_from_address(env, &peer_ss, peerIp, peerPort);
    return quiche_conn_is_path_validated(CONN(handle),
        (const struct sockaddr *)&local_ss, (size_t)local_len,
        (const struct sockaddr *)&peer_ss, (size_t)peer_len);
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeMigrateSource)(JNIEnv *env, jobject self, jlong handle,
        jbyteArray localIp, jint localPort) {
    struct sockaddr_storage local_ss;
    socklen_t local_len = fill_sockaddr_from_address(env, &local_ss, localIp, localPort);
    uint64_t seq = 0;
    int rc = quiche_conn_migrate_source(CONN(handle),
        (const struct sockaddr *)&local_ss, local_len, &seq);
    if (rc < 0) return (jlong)rc;
    return (jlong)seq;
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeMigrate)(JNIEnv *env, jobject self, jlong handle,
        jbyteArray localIp, jint localPort, jbyteArray peerIp, jint peerPort) {
    struct sockaddr_storage local_ss, peer_ss;
    socklen_t local_len = fill_sockaddr_from_address(env, &local_ss, localIp, localPort);
    socklen_t peer_len = fill_sockaddr_from_address(env, &peer_ss, peerIp, peerPort);
    uint64_t seq = 0;
    int rc = quiche_conn_migrate(CONN(handle),
        (const struct sockaddr *)&local_ss, local_len,
        (const struct sockaddr *)&peer_ss, peer_len, &seq);
    if (rc < 0) return (jlong)rc;
    return (jlong)seq;
}

// Returns a KichePathEvent subclass, or NULL if no event pending.
JNIEXPORT jobject JNICALL
JNI_FN(PKG, KicheConnection, nativePathEventNext)(JNIEnv *env, jobject self, jlong handle) {
    quiche_path_event *ev = quiche_conn_path_event_next(CONN(handle));
    if (!ev) return NULL;

    // Cached class/ctor IDs for the 5 simple event subclasses (local, peer) and ReusedSourceConnectionId
    static jclass clsNew = NULL, clsValidated = NULL, clsFailedValidation = NULL,
                  clsClosed = NULL, clsPeerMigrated = NULL, clsReused = NULL;
    static jmethodID ctorSimple = NULL, ctorReused = NULL;
    if (!clsNew) {
        const char *simpleCtorSig = "(Leu/buney/kiche/KicheAddress;Leu/buney/kiche/KicheAddress;)V";
        #define CACHE_SIMPLE(var, name) \
            var = (*env)->FindClass(env, "eu/buney/kiche/KichePathEvent$" name); \
            var = (jclass)(*env)->NewGlobalRef(env, (jobject)var);
        CACHE_SIMPLE(clsNew, "New")
        CACHE_SIMPLE(clsValidated, "Validated")
        CACHE_SIMPLE(clsFailedValidation, "FailedValidation")
        CACHE_SIMPLE(clsClosed, "Closed")
        CACHE_SIMPLE(clsPeerMigrated, "PeerMigrated")
        #undef CACHE_SIMPLE
        ctorSimple = (*env)->GetMethodID(env, clsNew, "<init>", simpleCtorSig);

        clsReused = (*env)->FindClass(env, "eu/buney/kiche/KichePathEvent$ReusedSourceConnectionId");
        clsReused = (jclass)(*env)->NewGlobalRef(env, (jobject)clsReused);
        ctorReused = (*env)->GetMethodID(env, clsReused, "<init>",
            "(JLeu/buney/kiche/KicheAddress;Leu/buney/kiche/KicheAddress;"
            "Leu/buney/kiche/KicheAddress;Leu/buney/kiche/KicheAddress;)V");
    }

    enum quiche_path_event_type type = quiche_path_event_type(ev);
    uint8_t ip_buf[16]; int ip_len, port;
    jobject result = NULL;

    if (type == QUICHE_PATH_EVENT_REUSED_SOURCE_CONNECTION_ID) {
        uint64_t id;
        struct sockaddr_storage old_local_ss, old_peer_ss, local_ss, peer_ss;
        socklen_t old_local_len, old_peer_len, local_len, peer_len;
        quiche_path_event_reused_source_connection_id(ev, &id,
            &old_local_ss, &old_local_len, &old_peer_ss, &old_peer_len,
            &local_ss, &local_len, &peer_ss, &peer_len);
        quiche_path_event_free(ev);

        extract_sockaddr(&old_local_ss, ip_buf, &ip_len, &port);
        jobject oldLocal = make_kiche_address(env, ip_buf, ip_len, port);
        extract_sockaddr(&old_peer_ss, ip_buf, &ip_len, &port);
        jobject oldPeer = make_kiche_address(env, ip_buf, ip_len, port);
        extract_sockaddr(&local_ss, ip_buf, &ip_len, &port);
        jobject local = make_kiche_address(env, ip_buf, ip_len, port);
        extract_sockaddr(&peer_ss, ip_buf, &ip_len, &port);
        jobject peer = make_kiche_address(env, ip_buf, ip_len, port);

        result = (*env)->NewObject(env, clsReused, ctorReused,
            (jlong)id, oldLocal, oldPeer, local, peer);
        (*env)->DeleteLocalRef(env, oldLocal);
        (*env)->DeleteLocalRef(env, oldPeer);
        (*env)->DeleteLocalRef(env, local);
        (*env)->DeleteLocalRef(env, peer);
    } else {
        struct sockaddr_storage local_ss, peer_ss;
        socklen_t local_len, peer_len;
        jclass eventCls;

        switch (type) {
            case QUICHE_PATH_EVENT_NEW:
                quiche_path_event_new(ev, &local_ss, &local_len, &peer_ss, &peer_len);
                eventCls = clsNew; break;
            case QUICHE_PATH_EVENT_VALIDATED:
                quiche_path_event_validated(ev, &local_ss, &local_len, &peer_ss, &peer_len);
                eventCls = clsValidated; break;
            case QUICHE_PATH_EVENT_FAILED_VALIDATION:
                quiche_path_event_failed_validation(ev, &local_ss, &local_len, &peer_ss, &peer_len);
                eventCls = clsFailedValidation; break;
            case QUICHE_PATH_EVENT_CLOSED:
                quiche_path_event_closed(ev, &local_ss, &local_len, &peer_ss, &peer_len);
                eventCls = clsClosed; break;
            case QUICHE_PATH_EVENT_PEER_MIGRATED:
                quiche_path_event_peer_migrated(ev, &local_ss, &local_len, &peer_ss, &peer_len);
                eventCls = clsPeerMigrated; break;
            default:
                quiche_path_event_free(ev);
                return NULL;
        }
        quiche_path_event_free(ev);

        extract_sockaddr(&local_ss, ip_buf, &ip_len, &port);
        jobject local = make_kiche_address(env, ip_buf, ip_len, port);
        extract_sockaddr(&peer_ss, ip_buf, &ip_len, &port);
        jobject peer = make_kiche_address(env, ip_buf, ip_len, port);

        result = (*env)->NewObject(env, eventCls, ctorSimple, local, peer);
        (*env)->DeleteLocalRef(env, local);
        (*env)->DeleteLocalRef(env, peer);
    }
    return result;
}

JNIEXPORT jobject JNICALL
JNI_FN(PKG, KicheConnection, nativeSendOnPath)(JNIEnv *env, jobject self, jlong handle,
        jbyteArray buf, jint len,
        jbyteArray fromIp, jint fromPort, jboolean hasFrom,
        jbyteArray toIp, jint toPort, jboolean hasTo) {
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);

    struct sockaddr_storage from_ss, to_ss;
    const struct sockaddr *from_ptr = NULL;
    socklen_t from_len = 0;
    const struct sockaddr *to_ptr = NULL;
    socklen_t to_len = 0;

    if (hasFrom && fromIp != NULL) {
        from_len = fill_sockaddr_from_address(env, &from_ss, fromIp, fromPort);
        from_ptr = (const struct sockaddr *)&from_ss;
    }
    if (hasTo && toIp != NULL) {
        to_len = fill_sockaddr_from_address(env, &to_ss, toIp, toPort);
        to_ptr = (const struct sockaddr *)&to_ss;
    }

    quiche_send_info si;
    ssize_t written = quiche_conn_send_on_path(CONN(handle), (uint8_t *)b, (size_t)len,
        from_ptr, from_len, to_ptr, to_len, &si);
    (*env)->ReleaseByteArrayElements(env, buf, b, 0);

    if (written == QUICHE_ERR_DONE) return NULL;
    if (written < 0) { throw_kiche_exception(env, (int)written); return NULL; }

    return make_send_result(env, (int)written, &si);
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConnection, nativeSendQuantumOnPath)(JNIEnv *env, jobject self, jlong handle,
        jbyteArray localIp, jint localPort, jbyteArray peerIp, jint peerPort) {
    struct sockaddr_storage local_ss, peer_ss;
    socklen_t local_len = fill_sockaddr_from_address(env, &local_ss, localIp, localPort);
    socklen_t peer_len = fill_sockaddr_from_address(env, &peer_ss, peerIp, peerPort);
    return (jlong)quiche_conn_send_quantum_on_path(CONN(handle),
        (const struct sockaddr *)&local_ss, (size_t)local_len,
        (const struct sockaddr *)&peer_ss, (size_t)peer_len);
}

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheConnection, nativeSendAckElicitingOnPath)(JNIEnv *env, jobject self, jlong handle,
        jbyteArray localIp, jint localPort, jbyteArray peerIp, jint peerPort) {
    struct sockaddr_storage local_ss, peer_ss;
    socklen_t local_len = fill_sockaddr_from_address(env, &local_ss, localIp, localPort);
    socklen_t peer_len = fill_sockaddr_from_address(env, &peer_ss, peerIp, peerPort);
    return quiche_conn_send_ack_eliciting_on_path(CONN(handle),
        (const struct sockaddr *)&local_ss, (size_t)local_len,
        (const struct sockaddr *)&peer_ss, (size_t)peer_len);
}

JNIEXPORT jobjectArray JNICALL
JNI_FN(PKG, KicheConnection, nativePathsIter)(JNIEnv *env, jobject self, jlong handle,
        jbyteArray fromIp, jint fromPort) {
    struct sockaddr_storage from_ss;
    socklen_t from_len = fill_sockaddr_from_address(env, &from_ss, fromIp, fromPort);

    quiche_socket_addr_iter *iter = quiche_conn_paths_iter(CONN(handle),
        (const struct sockaddr *)&from_ss, (size_t)from_len);
    if (!iter) return NULL;

    // Collect all addresses from iterator (typically 1-4 paths)
    struct sockaddr_storage addrs[16];
    int count = 0;
    size_t peer_len;
    while (count < 16 && quiche_socket_addr_iter_next(iter, &addrs[count], &peer_len)) {
        count++;
    }
    quiche_socket_addr_iter_free(iter);

    if (count == 0) return NULL;

    jclass addrCls = (*env)->FindClass(env, "eu/buney/kiche/KicheAddress");
    jobjectArray result = (*env)->NewObjectArray(env, count, addrCls, NULL);
    for (int i = 0; i < count; i++) {
        uint8_t ip_buf[16]; int ip_len, port;
        extract_sockaddr(&addrs[i], ip_buf, &ip_len, &port);
        jobject addr = make_kiche_address(env, ip_buf, ip_len, port);
        (*env)->SetObjectArrayElement(env, result, i, addr);
        (*env)->DeleteLocalRef(env, addr);
    }
    return result;
}
