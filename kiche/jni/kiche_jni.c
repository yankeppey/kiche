#include "kiche_jni.h"
#include <stdio.h>
#include <string.h>

static void debug_log_callback(const char *line, void *argp) {
    fprintf(stderr, "%s\n", line);
}

JNIEXPORT jint JNICALL
JNI_FN(PKG, Kiche, nativeEnableDebugLogging)(JNIEnv *env, jclass clazz) {
    return quiche_enable_debug_logging(debug_log_callback, NULL);
}

JNIEXPORT jstring JNICALL
JNI_FN(PKG, Kiche, nativeQuicheVersion)(JNIEnv *env, jclass clazz) {
    const char *version = quiche_version();
    return (*env)->NewStringUTF(env, version);
}

JNIEXPORT jboolean JNICALL
JNI_FN(PKG, Kiche, nativeVersionIsSupported)(JNIEnv *env, jclass clazz, jint version) {
    return quiche_version_is_supported((uint32_t)version);
}

// Populates out_meta[0]=version, out_meta[1]=type, and returns scid, dcid, token
// via output byte arrays. Returns 0 on success, negative error code on failure.
JNIEXPORT jint JNICALL
JNI_FN(PKG, Kiche, nativeHeaderInfo)(JNIEnv *env, jclass clazz,
        jbyteArray buf, jint len, jint dcil,
        jintArray outMeta, jbyteArray outScid, jbyteArray outDcid, jbyteArray outToken,
        jintArray outLens) {
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);

    uint32_t version = 0;
    uint8_t type = 0;
    uint8_t scid[QUICHE_MAX_CONN_ID_LEN];
    size_t scid_len = sizeof(scid);
    uint8_t dcid[QUICHE_MAX_CONN_ID_LEN];
    size_t dcid_len = sizeof(dcid);
    uint8_t token[512];
    size_t token_len = sizeof(token);

    int rc = quiche_header_info((uint8_t *)b, (size_t)len, (size_t)dcil,
        &version, &type, scid, &scid_len, dcid, &dcid_len, token, &token_len);
    (*env)->ReleaseByteArrayElements(env, buf, b, JNI_ABORT);

    if (rc < 0) return rc;

    jint meta[2] = { (jint)version, (jint)type };
    (*env)->SetIntArrayRegion(env, outMeta, 0, 2, meta);

    if (scid_len > 0) (*env)->SetByteArrayRegion(env, outScid, 0, (jsize)scid_len, (const jbyte *)scid);
    if (dcid_len > 0) (*env)->SetByteArrayRegion(env, outDcid, 0, (jsize)dcid_len, (const jbyte *)dcid);
    if (token_len > 0) (*env)->SetByteArrayRegion(env, outToken, 0, (jsize)token_len, (const jbyte *)token);

    jint lens[3] = { (jint)scid_len, (jint)dcid_len, (jint)token_len };
    (*env)->SetIntArrayRegion(env, outLens, 0, 3, lens);

    return 0;
}

JNIEXPORT jint JNICALL
JNI_FN(PKG, Kiche, nativeNegotiateVersion)(JNIEnv *env, jclass clazz,
        jbyteArray scid, jbyteArray dcid, jbyteArray out) {
    jbyte *s = (*env)->GetByteArrayElements(env, scid, NULL);
    jsize slen = (*env)->GetArrayLength(env, scid);
    jbyte *d = (*env)->GetByteArrayElements(env, dcid, NULL);
    jsize dlen = (*env)->GetArrayLength(env, dcid);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    jsize olen = (*env)->GetArrayLength(env, out);

    ssize_t written = quiche_negotiate_version(
        (const uint8_t *)s, (size_t)slen,
        (const uint8_t *)d, (size_t)dlen,
        (uint8_t *)o, (size_t)olen);

    (*env)->ReleaseByteArrayElements(env, scid, s, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, dcid, d, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);

    return (jint)written;
}

JNIEXPORT jint JNICALL
JNI_FN(PKG, Kiche, nativeRetry)(JNIEnv *env, jclass clazz,
        jbyteArray scid, jbyteArray dcid, jbyteArray newScid,
        jbyteArray token, jint version, jbyteArray out) {
    jbyte *s = (*env)->GetByteArrayElements(env, scid, NULL);
    jsize slen = (*env)->GetArrayLength(env, scid);
    jbyte *d = (*env)->GetByteArrayElements(env, dcid, NULL);
    jsize dlen = (*env)->GetArrayLength(env, dcid);
    jbyte *ns = (*env)->GetByteArrayElements(env, newScid, NULL);
    jsize nslen = (*env)->GetArrayLength(env, newScid);
    jbyte *t = (*env)->GetByteArrayElements(env, token, NULL);
    jsize tlen = (*env)->GetArrayLength(env, token);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    jsize olen = (*env)->GetArrayLength(env, out);

    ssize_t written = quiche_retry(
        (const uint8_t *)s, (size_t)slen,
        (const uint8_t *)d, (size_t)dlen,
        (const uint8_t *)ns, (size_t)nslen,
        (const uint8_t *)t, (size_t)tlen,
        (uint32_t)version,
        (uint8_t *)o, (size_t)olen);

    (*env)->ReleaseByteArrayElements(env, scid, s, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, dcid, d, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, newScid, ns, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, token, t, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);

    return (jint)written;
}
