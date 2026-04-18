#ifndef KICHE_JNI_H
#define KICHE_JNI_H

#include <jni.h>
#include "quiche.h"

#define PKG eu_buney_kiche
#define JNI_PASTE(p, c, m) Java_##p##_##c##_##m
#define JNI_FN(p, c, m)    JNI_PASTE(p, c, m)

#endif // KICHE_JNI_H
