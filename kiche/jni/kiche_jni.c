#include "kiche_jni.h"

JNIEXPORT jstring JNICALL
JNI_FN(PKG, Kiche, nativeQuicheVersion)(JNIEnv *env, jclass clazz) {
    const char *version = quiche_version();
    return (*env)->NewStringUTF(env, version);
}
