#include <jni.h>
#include "quiche.h"

JNIEXPORT jstring JNICALL
Java_eu_buney_kiche_Kiche_nativeQuicheVersion(JNIEnv *env, jclass clazz) {
    const char *version = quiche_version();
    return (*env)->NewStringUTF(env, version);
}
