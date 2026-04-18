#include "kiche_jni.h"

JNIEXPORT jstring JNICALL
JNI_FN(PKG, Kiche, nativeQuicheVersion)(JNIEnv *env, jclass clazz) {
    const char *version = quiche_version();
    return (*env)->NewStringUTF(env, version);
}

JNIEXPORT jboolean JNICALL
JNI_FN(PKG, Kiche, nativeVersionIsSupported)(JNIEnv *env, jclass clazz, jint version) {
    return quiche_version_is_supported((uint32_t)version);
}
