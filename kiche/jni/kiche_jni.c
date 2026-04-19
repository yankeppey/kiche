#include "kiche_jni.h"
#include <stdio.h>

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
