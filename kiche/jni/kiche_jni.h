#ifndef KICHE_JNI_H
#define KICHE_JNI_H

#include <jni.h>
#include "quiche.h"

#define PKG eu_buney_kiche
#define JNI_PASTE(p, c, m) Java_##p##_##c##_##m
#define JNI_FN(p, c, m)    JNI_PASTE(p, c, m)

// helpers.c
socklen_t fill_sockaddr(struct sockaddr_storage *ss, const uint8_t *ip, int ip_len, int port);
void extract_sockaddr(const struct sockaddr_storage *ss, uint8_t *out_ip, int *out_ip_len, int *out_port);
jobject make_kiche_address(JNIEnv *env, const uint8_t *ip, int ip_len, int port);
jobject make_send_result(JNIEnv *env, int written, const quiche_send_info *si);
void throw_kiche_exception(JNIEnv *env, int code);
void throw_kiche_h3_exception(JNIEnv *env, int code);
socklen_t fill_sockaddr_from_address(JNIEnv *env, struct sockaddr_storage *ss, jbyteArray ip, jint port);

#endif // KICHE_JNI_H
