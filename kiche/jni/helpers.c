#include "kiche_jni.h"
#include <string.h>
#include <arpa/inet.h>
#include <netinet/in.h>

// Fill a sockaddr from ip bytes + port.
// ip must be 4 bytes (IPv4) or 16 bytes (IPv6).
// Returns the socklen_t of the filled sockaddr.
socklen_t fill_sockaddr(struct sockaddr_storage *ss, const uint8_t *ip, int ip_len, int port) {
    memset(ss, 0, sizeof(*ss));
    if (ip_len == 4) {
        struct sockaddr_in *sa = (struct sockaddr_in *)ss;
        sa->sin_family = AF_INET;
        sa->sin_port = htons((uint16_t)port);
        memcpy(&sa->sin_addr, ip, 4);
        return sizeof(struct sockaddr_in);
    } else {
        struct sockaddr_in6 *sa6 = (struct sockaddr_in6 *)ss;
        sa6->sin6_family = AF_INET6;
        sa6->sin6_port = htons((uint16_t)port);
        memcpy(&sa6->sin6_addr, ip, 16);
        return sizeof(struct sockaddr_in6);
    }
}

// Extract ip bytes and port from a sockaddr_storage.
// Writes ip into out_ip (must be at least 16 bytes), sets out_ip_len and out_port.
void extract_sockaddr(const struct sockaddr_storage *ss,
                      uint8_t *out_ip, int *out_ip_len, int *out_port) {
    if (ss->ss_family == AF_INET) {
        const struct sockaddr_in *sa = (const struct sockaddr_in *)ss;
        memcpy(out_ip, &sa->sin_addr, 4);
        *out_ip_len = 4;
        *out_port = ntohs(sa->sin_port);
    } else {
        const struct sockaddr_in6 *sa6 = (const struct sockaddr_in6 *)ss;
        memcpy(out_ip, &sa6->sin6_addr, 16);
        *out_ip_len = 16;
        *out_port = ntohs(sa6->sin6_port);
    }
}

// Helper to create a KicheAddress Java object from ip + port.
jobject make_kiche_address(JNIEnv *env, const uint8_t *ip, int ip_len, int port) {
    static jclass cls = NULL;
    static jmethodID ctor = NULL;
    if (!cls) {
        cls = (*env)->FindClass(env, "eu/buney/kiche/KicheAddress");
        cls = (jclass)(*env)->NewGlobalRef(env, (jobject)cls);
        ctor = (*env)->GetMethodID(env, cls, "<init>", "([BI)V");
    }
    jbyteArray ipArr = (*env)->NewByteArray(env, ip_len);
    (*env)->SetByteArrayRegion(env, ipArr, 0, ip_len, (const jbyte *)ip);
    return (*env)->NewObject(env, cls, ctor, ipArr, port);
}

// Helper to fill sockaddr from a KicheAddress Java object.
socklen_t fill_sockaddr_from_address(JNIEnv *env, struct sockaddr_storage *ss,
                                     jbyteArray ip, jint port) {
    jsize ip_len = (*env)->GetArrayLength(env, ip);
    jbyte *ip_buf = (*env)->GetByteArrayElements(env, ip, NULL);
    socklen_t len = fill_sockaddr(ss, (const uint8_t *)ip_buf, ip_len, port);
    (*env)->ReleaseByteArrayElements(env, ip, ip_buf, JNI_ABORT);
    return len;
}
