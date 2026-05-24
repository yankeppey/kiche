#include "kiche_jni.h"
#include <string.h>
#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#endif

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

// Build a KicheSendResult Java object from written count + quiche_send_info.
// An alternative approach is to pass pre-allocated reusable buffers from Kotlin and fill
// them in C (avoids NewObject/NewByteArray allocations). We chose object construction here
// because the marginal allocation cost (~300-500ns) is dwarfed by quiche_conn_send itself
// (~1-10us for encryption + framing), and it yields a cleaner Kotlin API with no mutable
// sidecar state on KicheConnection.
jobject make_send_result(JNIEnv *env, int written, const quiche_send_info *si) {
    static jclass cls = NULL;
    static jmethodID ctor = NULL;
    if (!cls) {
        cls = (*env)->FindClass(env, "eu/buney/kiche/KicheSendResult");
        cls = (jclass)(*env)->NewGlobalRef(env, (jobject)cls);
        ctor = (*env)->GetMethodID(env, cls, "<init>",
            "(ILeu/buney/kiche/KicheAddress;Leu/buney/kiche/KicheAddress;J)V");
    }

    uint8_t ip_buf[16]; int ip_len, port;

    extract_sockaddr(&si->from, ip_buf, &ip_len, &port);
    jobject fromAddr = make_kiche_address(env, ip_buf, ip_len, port);

    extract_sockaddr(&si->to, ip_buf, &ip_len, &port);
    jobject toAddr = make_kiche_address(env, ip_buf, ip_len, port);

    long at_nanos = (long)si->at.tv_sec * 1000000000L + (long)si->at.tv_nsec;

    jobject result = (*env)->NewObject(env, cls, ctor,
        (jint)written, fromAddr, toAddr, (jlong)at_nanos);

    (*env)->DeleteLocalRef(env, fromAddr);
    (*env)->DeleteLocalRef(env, toAddr);
    return result;
}

// Throw a KicheException for a quiche error code by calling KicheException.check().
// After this call, a Java exception is pending — the caller must return immediately.
void throw_kiche_exception(JNIEnv *env, int code) {
    static jclass cls = NULL;
    static jmethodID checkMethod = NULL;
    if (!cls) {
        cls = (*env)->FindClass(env, "eu/buney/kiche/KicheException");
        cls = (jclass)(*env)->NewGlobalRef(env, (jobject)cls);
        checkMethod = (*env)->GetStaticMethodID(env, cls, "check", "(I)V");
    }
    (*env)->CallStaticVoidMethod(env, cls, checkMethod, (jint)code);
}

// Build a KicheH3Event Java object from event type int, stream id, and optional headers list.
// eventTypeValue is the quiche H3 event type integer; headerList may be NULL.
jobject make_h3_event(JNIEnv *env, int eventTypeValue, int64_t streamId, jobject headerList) {
    static jclass eventCls = NULL, eventTypeCls = NULL;
    static jmethodID eventCtor = NULL, fromValueMethod = NULL;
    if (!eventCls) {
        eventCls = (*env)->FindClass(env, "eu/buney/kiche/KicheH3Event");
        eventCls = (jclass)(*env)->NewGlobalRef(env, (jobject)eventCls);
        eventCtor = (*env)->GetMethodID(env, eventCls, "<init>",
            "(Leu/buney/kiche/KicheH3EventType;JLjava/util/List;)V");

        eventTypeCls = (*env)->FindClass(env, "eu/buney/kiche/KicheH3EventType");
        eventTypeCls = (jclass)(*env)->NewGlobalRef(env, (jobject)eventTypeCls);
        fromValueMethod = (*env)->GetStaticMethodID(env, eventTypeCls, "fromValue",
            "(I)Leu/buney/kiche/KicheH3EventType;");
    }

    jobject eventTypeObj = (*env)->CallStaticObjectMethod(env, eventTypeCls,
        fromValueMethod, (jint)eventTypeValue);
    if (!eventTypeObj) return NULL;

    return (*env)->NewObject(env, eventCls, eventCtor,
        eventTypeObj, (jlong)streamId, headerList);
}

// Throw a KicheH3Exception for an H3 error code by calling KicheH3Exception.check().
// After this call, a Java exception is pending — the caller must return immediately.
void throw_kiche_h3_exception(JNIEnv *env, int code) {
    static jclass cls = NULL;
    static jmethodID checkMethod = NULL;
    if (!cls) {
        cls = (*env)->FindClass(env, "eu/buney/kiche/KicheH3Exception");
        cls = (jclass)(*env)->NewGlobalRef(env, (jobject)cls);
        checkMethod = (*env)->GetStaticMethodID(env, cls, "check", "(I)V");
    }
    (*env)->CallStaticVoidMethod(env, cls, checkMethod, (jint)code);
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
