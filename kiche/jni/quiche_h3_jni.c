#include "kiche_jni.h"
#include <stdlib.h>
#include <string.h>

#define H3(handle)   ((quiche_h3_conn *)(intptr_t)(handle))
#define H3CFG(handle) ((quiche_h3_config *)(intptr_t)(handle))
#define CONN(handle) ((quiche_conn *)(intptr_t)(handle))

// ── H3 Config ──

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheH3Config, nativeNew)(JNIEnv *env, jobject self) {
    return (jlong)(intptr_t)quiche_h3_config_new();
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheH3Config, nativeFree)(JNIEnv *env, jobject self, jlong handle) {
    quiche_h3_config_free(H3CFG(handle));
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheH3Config, nativeSetMaxFieldSectionSize)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_h3_config_set_max_field_section_size(H3CFG(handle), (uint64_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheH3Config, nativeSetQpackMaxTableCapacity)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_h3_config_set_qpack_max_table_capacity(H3CFG(handle), (uint64_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheH3Config, nativeSetQpackBlockedStreams)(JNIEnv *env, jobject self, jlong handle, jlong v) {
    quiche_h3_config_set_qpack_blocked_streams(H3CFG(handle), (uint64_t)v);
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheH3Config, nativeEnableExtendedConnect)(JNIEnv *env, jobject self, jlong handle, jboolean v) {
    quiche_h3_config_enable_extended_connect(H3CFG(handle), v);
}

// ── H3 Connection ──

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheH3Connection, nativeNew)(JNIEnv *env, jobject self, jlong quicConn, jlong config) {
    return (jlong)(intptr_t)quiche_h3_conn_new_with_transport(CONN(quicConn), H3CFG(config));
}

JNIEXPORT void JNICALL
JNI_FN(PKG, KicheH3Connection, nativeFree)(JNIEnv *env, jobject self, jlong handle) {
    quiche_h3_conn_free(H3(handle));
}

// Header collection callback context
typedef struct {
    JNIEnv *env;
    jobject list;      // ArrayList<KicheH3Header>
    jmethodID addMethod;
    jclass headerClass;
    jmethodID headerCtor;
} header_cb_ctx;

static int header_collect_cb(uint8_t *name, size_t name_len,
                              uint8_t *value, size_t value_len,
                              void *argp) {
    header_cb_ctx *ctx = (header_cb_ctx *)argp;
    JNIEnv *env = ctx->env;

    jbyteArray jname = (*env)->NewByteArray(env, (jsize)name_len);
    (*env)->SetByteArrayRegion(env, jname, 0, (jsize)name_len, (const jbyte *)name);

    jbyteArray jvalue = (*env)->NewByteArray(env, (jsize)value_len);
    (*env)->SetByteArrayRegion(env, jvalue, 0, (jsize)value_len, (const jbyte *)value);

    jobject header = (*env)->NewObject(env, ctx->headerClass, ctx->headerCtor, jname, jvalue);
    (*env)->CallBooleanMethod(env, ctx->list, ctx->addMethod, header);
    return 0;
}

JNIEXPORT jlongArray JNICALL
JNI_FN(PKG, KicheH3Connection, nativePoll)(JNIEnv *env, jobject self,
        jlong handle, jlong quicConn) {
    quiche_h3_event *ev = NULL;
    int64_t stream_id = quiche_h3_conn_poll(H3(handle), CONN(quicConn), &ev);

    if (stream_id < 0) {
        // DONE or error
        return NULL;
    }

    int event_type = (int)quiche_h3_event_type(ev);

    // If headers event, collect headers eagerly
    if (event_type == QUICHE_H3_EVENT_HEADERS) {
        // Create ArrayList
        jclass listCls = (*env)->FindClass(env, "java/util/ArrayList");
        jmethodID listCtor = (*env)->GetMethodID(env, listCls, "<init>", "()V");
        jmethodID listAdd = (*env)->GetMethodID(env, listCls, "add", "(Ljava/lang/Object;)Z");
        jobject list = (*env)->NewObject(env, listCls, listCtor);

        jclass headerCls = (*env)->FindClass(env, "eu/buney/kiche/KicheH3Header");
        jmethodID headerCtor = (*env)->GetMethodID(env, headerCls, "<init>", "([B[B)V");

        header_cb_ctx ctx = {
            .env = env,
            .list = list,
            .addMethod = listAdd,
            .headerClass = headerCls,
            .headerCtor = headerCtor,
        };

        quiche_h3_event_for_each_header(ev, header_collect_cb, &ctx);

        // Store in lastHeaders field
        static jfieldID fHeaders = NULL;
        if (!fHeaders) {
            jclass connCls = (*env)->GetObjectClass(env, self);
            fHeaders = (*env)->GetFieldID(env, connCls, "lastHeaders", "Ljava/util/List;");
        }
        (*env)->SetObjectField(env, self, fHeaders, list);
    }

    quiche_h3_event_free(ev);

    jlongArray result = (*env)->NewLongArray(env, 2);
    jlong vals[2] = { stream_id, event_type };
    (*env)->SetLongArrayRegion(env, result, 0, 2, vals);
    return result;
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheH3Connection, nativeSendRequest)(JNIEnv *env, jobject self,
        jlong handle, jlong quicConn,
        jobjectArray names, jobjectArray values, jboolean fin) {
    jsize count = (*env)->GetArrayLength(env, names);
    quiche_h3_header *hdrs = (quiche_h3_header *)calloc(count, sizeof(quiche_h3_header));
    jbyte **name_bufs = (jbyte **)calloc(count, sizeof(jbyte *));
    jbyte **value_bufs = (jbyte **)calloc(count, sizeof(jbyte *));
    jbyteArray *name_arrs = (jbyteArray *)calloc(count, sizeof(jbyteArray));
    jbyteArray *value_arrs = (jbyteArray *)calloc(count, sizeof(jbyteArray));

    for (jsize i = 0; i < count; i++) {
        name_arrs[i] = (jbyteArray)(*env)->GetObjectArrayElement(env, names, i);
        value_arrs[i] = (jbyteArray)(*env)->GetObjectArrayElement(env, values, i);
        name_bufs[i] = (*env)->GetByteArrayElements(env, name_arrs[i], NULL);
        value_bufs[i] = (*env)->GetByteArrayElements(env, value_arrs[i], NULL);
        hdrs[i].name = (const uint8_t *)name_bufs[i];
        hdrs[i].name_len = (*env)->GetArrayLength(env, name_arrs[i]);
        hdrs[i].value = (const uint8_t *)value_bufs[i];
        hdrs[i].value_len = (*env)->GetArrayLength(env, value_arrs[i]);
    }

    int64_t rc = quiche_h3_send_request(H3(handle), CONN(quicConn), hdrs, (size_t)count, fin);

    for (jsize i = 0; i < count; i++) {
        (*env)->ReleaseByteArrayElements(env, name_arrs[i], name_bufs[i], JNI_ABORT);
        (*env)->ReleaseByteArrayElements(env, value_arrs[i], value_bufs[i], JNI_ABORT);
    }
    free(hdrs);
    free(name_bufs);
    free(value_bufs);
    free(name_arrs);
    free(value_arrs);
    return (jlong)rc;
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheH3Connection, nativeSendBody)(JNIEnv *env, jobject self,
        jlong handle, jlong quicConn, jlong streamId, jbyteArray body, jboolean fin) {
    jbyte *b = (*env)->GetByteArrayElements(env, body, NULL);
    jsize len = (*env)->GetArrayLength(env, body);
    ssize_t rc = quiche_h3_send_body(H3(handle), CONN(quicConn),
        (uint64_t)streamId, (const uint8_t *)b, (size_t)len, fin);
    (*env)->ReleaseByteArrayElements(env, body, b, JNI_ABORT);
    return (jlong)rc;
}

JNIEXPORT jlong JNICALL
JNI_FN(PKG, KicheH3Connection, nativeRecvBody)(JNIEnv *env, jobject self,
        jlong handle, jlong quicConn, jlong streamId, jbyteArray buf) {
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);
    jsize len = (*env)->GetArrayLength(env, buf);
    ssize_t rc = quiche_h3_recv_body(H3(handle), CONN(quicConn),
        (uint64_t)streamId, (uint8_t *)b, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, buf, b, 0);
    return (jlong)rc;
}

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheH3Connection, nativeSendResponse)(JNIEnv *env, jobject self,
        jlong handle, jlong quicConn, jlong streamId,
        jobjectArray names, jobjectArray values, jboolean fin) {
    jsize count = (*env)->GetArrayLength(env, names);
    quiche_h3_header *hdrs = (quiche_h3_header *)calloc(count, sizeof(quiche_h3_header));
    jbyte **name_bufs = (jbyte **)calloc(count, sizeof(jbyte *));
    jbyte **value_bufs = (jbyte **)calloc(count, sizeof(jbyte *));
    jbyteArray *name_arrs = (jbyteArray *)calloc(count, sizeof(jbyteArray));
    jbyteArray *value_arrs = (jbyteArray *)calloc(count, sizeof(jbyteArray));

    for (jsize i = 0; i < count; i++) {
        name_arrs[i] = (jbyteArray)(*env)->GetObjectArrayElement(env, names, i);
        value_arrs[i] = (jbyteArray)(*env)->GetObjectArrayElement(env, values, i);
        name_bufs[i] = (*env)->GetByteArrayElements(env, name_arrs[i], NULL);
        value_bufs[i] = (*env)->GetByteArrayElements(env, value_arrs[i], NULL);
        hdrs[i].name = (const uint8_t *)name_bufs[i];
        hdrs[i].name_len = (*env)->GetArrayLength(env, name_arrs[i]);
        hdrs[i].value = (const uint8_t *)value_bufs[i];
        hdrs[i].value_len = (*env)->GetArrayLength(env, value_arrs[i]);
    }

    int rc = quiche_h3_send_response(H3(handle), CONN(quicConn),
        (uint64_t)streamId, hdrs, (size_t)count, fin);

    for (jsize i = 0; i < count; i++) {
        (*env)->ReleaseByteArrayElements(env, name_arrs[i], name_bufs[i], JNI_ABORT);
        (*env)->ReleaseByteArrayElements(env, value_arrs[i], value_bufs[i], JNI_ABORT);
    }
    free(hdrs);
    free(name_bufs);
    free(value_bufs);
    free(name_arrs);
    free(value_arrs);
    return rc;
}

JNIEXPORT jint JNICALL
JNI_FN(PKG, KicheH3Connection, nativeSendGoaway)(JNIEnv *env, jobject self,
        jlong handle, jlong quicConn, jlong id) {
    return quiche_h3_send_goaway(H3(handle), CONN(quicConn), (uint64_t)id);
}

JNIEXPORT jboolean JNICALL
JNI_FN(PKG, KicheH3Connection, nativeDgramEnabledByPeer)(JNIEnv *env, jobject self,
        jlong handle, jlong quicConn) {
    return quiche_h3_dgram_enabled_by_peer(H3(handle), CONN(quicConn));
}

JNIEXPORT jlongArray JNICALL
JNI_FN(PKG, KicheH3Connection, nativeStats)(JNIEnv *env, jobject self, jlong handle) {
    quiche_h3_stats stats;
    quiche_h3_conn_stats(H3(handle), &stats);
    jlongArray result = (*env)->NewLongArray(env, 2);
    jlong vals[2] = {
        (jlong)stats.qpack_encoder_stream_recv_bytes,
        (jlong)stats.qpack_decoder_stream_recv_bytes,
    };
    (*env)->SetLongArrayRegion(env, result, 0, 2, vals);
    return result;
}
