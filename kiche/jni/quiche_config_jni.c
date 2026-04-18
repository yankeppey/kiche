#include "kiche_jni.h"


#define CFG(handle) ((quiche_config *)(intptr_t)(handle))

// --- Lifecycle ---

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheConfig, nativeNew)(JNIEnv *env, jobject self, jint version) {
    quiche_config *cfg = quiche_config_new((uint32_t)version);
    return (jlong)(intptr_t)cfg;
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeFree)(JNIEnv *env, jobject self, jlong handle) {
    quiche_config_free(CFG(handle));
}

// --- Certificate / TLS ---

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheConfig, nativeLoadCertChainFromPemFile)(JNIEnv *env, jobject self, jlong handle, jstring path) {
    const char *c_path = (*env)->GetStringUTFChars(env, path, NULL);
    int rc = quiche_config_load_cert_chain_from_pem_file(CFG(handle), c_path);
    (*env)->ReleaseStringUTFChars(env, path, c_path);
    return rc;
}

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheConfig, nativeLoadPrivKeyFromPemFile)(JNIEnv *env, jobject self, jlong handle, jstring path) {
    const char *c_path = (*env)->GetStringUTFChars(env, path, NULL);
    int rc = quiche_config_load_priv_key_from_pem_file(CFG(handle), c_path);
    (*env)->ReleaseStringUTFChars(env, path, c_path);
    return rc;
}

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheConfig, nativeLoadVerifyLocationsFromFile)(JNIEnv *env, jobject self, jlong handle, jstring path) {
    const char *c_path = (*env)->GetStringUTFChars(env, path, NULL);
    int rc = quiche_config_load_verify_locations_from_file(CFG(handle), c_path);
    (*env)->ReleaseStringUTFChars(env, path, c_path);
    return rc;
}

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheConfig, nativeLoadVerifyLocationsFromDirectory)(JNIEnv *env, jobject self, jlong handle, jstring path) {
    const char *c_path = (*env)->GetStringUTFChars(env, path, NULL);
    int rc = quiche_config_load_verify_locations_from_directory(CFG(handle), c_path);
    (*env)->ReleaseStringUTFChars(env, path, c_path);
    return rc;
}

// --- Boolean setters ---

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeVerifyPeer)(JNIEnv *env, jobject self, jlong handle, jboolean v) {
    quiche_config_verify_peer(CFG(handle), v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeGrease)(JNIEnv *env, jobject self, jlong handle, jboolean v) {
    quiche_config_grease(CFG(handle), v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeDiscoverPmtu)(JNIEnv *env, jobject self, jlong handle, jboolean v) {
    quiche_config_discover_pmtu(CFG(handle), v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeLogKeys)(JNIEnv *env, jobject self, jlong handle) {
    quiche_config_log_keys(CFG(handle));
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeEnableEarlyData)(JNIEnv *env, jobject self, jlong handle) {
    quiche_config_enable_early_data(CFG(handle));
}

// --- Application protocols ---

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheConfig, nativeSetApplicationProtos)(JNIEnv *env, jobject self, jlong handle, jbyteArray protos) {
    jbyte *buf = (*env)->GetByteArrayElements(env, protos, NULL);
    jsize len = (*env)->GetArrayLength(env, protos);
    int rc = quiche_config_set_application_protos(CFG(handle), (const uint8_t *)buf, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, protos, buf, JNI_ABORT);
    return rc;
}

// --- Numeric setters ---

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetMaxAmplificationFactor)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_max_amplification_factor(CFG(handle), (size_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetMaxIdleTimeout)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_max_idle_timeout(CFG(handle), (uint64_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetMaxRecvUdpPayloadSize)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_max_recv_udp_payload_size(CFG(handle), (size_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetMaxSendUdpPayloadSize)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_max_send_udp_payload_size(CFG(handle), (size_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetInitialMaxData)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_initial_max_data(CFG(handle), (uint64_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetInitialMaxStreamDataBidiLocal)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_initial_max_stream_data_bidi_local(CFG(handle), (uint64_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetInitialMaxStreamDataBidiRemote)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_initial_max_stream_data_bidi_remote(CFG(handle), (uint64_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetInitialMaxStreamDataUni)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_initial_max_stream_data_uni(CFG(handle), (uint64_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetInitialMaxStreamsBidi)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_initial_max_streams_bidi(CFG(handle), (uint64_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetInitialMaxStreamsUni)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_initial_max_streams_uni(CFG(handle), (uint64_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetAckDelayExponent)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_ack_delay_exponent(CFG(handle), (uint64_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetMaxAckDelay)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_max_ack_delay(CFG(handle), (uint64_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetDisableActiveMigration)(JNIEnv *env, jobject self, jlong handle, jboolean v) {
    quiche_config_set_disable_active_migration(CFG(handle), v);
}

// --- Congestion control ---

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetCcAlgorithm)(JNIEnv *env, jobject self, jlong handle, jint algo) {
    quiche_config_set_cc_algorithm(CFG(handle), (enum quiche_cc_algorithm)algo);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetInitialCongestionWindowPackets)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_initial_congestion_window_packets(CFG(handle), (size_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeEnableHystart)(JNIEnv *env, jobject self, jlong handle, jboolean v) {
    quiche_config_enable_hystart(CFG(handle), v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeEnablePacing)(JNIEnv *env, jobject self, jlong handle, jboolean v) {
    quiche_config_enable_pacing(CFG(handle), v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetMaxPacingRate)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_max_pacing_rate(CFG(handle), (uint64_t)v);
}

// --- Datagrams ---

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeEnableDgram)(JNIEnv *env, jobject self, jlong handle, jboolean enabled, jlong recvQueueLen, jlong sendQueueLen) {
    quiche_config_enable_dgram(CFG(handle), enabled, (size_t)recvQueueLen, (size_t)sendQueueLen);
}

// --- Window / connection ID ---

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetMaxConnectionWindow)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_max_connection_window(CFG(handle), (uint64_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetMaxStreamWindow)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_max_stream_window(CFG(handle), (uint64_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetActiveConnectionIdLimit)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_config_set_active_connection_id_limit(CFG(handle), (uint64_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetStatelessResetToken)(JNIEnv *env, jobject self, jlong handle, jbyteArray token) {
    jbyte *buf = (*env)->GetByteArrayElements(env, token, NULL);
    quiche_config_set_stateless_reset_token(CFG(handle), (const uint8_t *)buf);
    (*env)->ReleaseByteArrayElements(env, token, buf, JNI_ABORT);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheConfig, nativeSetDisableDcidReuse)(JNIEnv *env, jobject self, jlong handle, jboolean v) {
    quiche_config_set_disable_dcid_reuse(CFG(handle), v);
}
