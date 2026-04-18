#ifndef KICHE_NET_H
#define KICHE_NET_H

#include <stdint.h>

// htons/ntohs are C macros that Kotlin/Native cinterop cannot import.
// We re-export them as inline functions so they're available in Kotlin.

static inline uint16_t kiche_htons(uint16_t v) {
    return (uint16_t)((v >> 8) | (v << 8));
}

static inline uint16_t kiche_ntohs(uint16_t v) {
    return kiche_htons(v);
}

#endif // KICHE_NET_H
