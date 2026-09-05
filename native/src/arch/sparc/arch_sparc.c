#include "native_accelerator.h"

#include <stddef.h>
#include <stdint.h>

/*
 * SPARC architecture backend shared by Solaris/illumos, Linux and BSD.
 * OS-specific facilities such as Solaris DAX deliberately do not live here.
 * Explicit VIS/VIS2/VIS3 kernels can be added in this directory later while
 * keeping the OS layer unchanged.
 */

uint64_t na_arch_capabilities(void) {
    uint64_t caps = NA_CAP_SIMD | NA_CAP_SPARC_VIS | NA_CAP_BIENDIAN_ARCH;

#if defined(__VIS) || defined(__VIS__)
    caps |= NA_CAP_SPARC_VIS2;
#endif
#if defined(__VIS3) || defined(__VIS3__)
    caps |= NA_CAP_SPARC_VIS3;
#endif

    return caps;
}

const char *na_arch_backend_name(void) {
#if defined(__VIS3) || defined(__VIS3__)
    return "sparcv9-vis3";
#elif defined(__VIS) || defined(__VIS__)
    return "sparcv9-vis2";
#else
    return "sparcv9-vis";
#endif
}

void na_arch_xor_bytes(uint8_t *dst, const uint8_t *left, const uint8_t *right, size_t length) {
    size_t i = 0;
    for (; i + sizeof(uint64_t) <= length; i += sizeof(uint64_t)) {
        uint64_t a, b, c;
        __builtin_memcpy(&a, left + i, sizeof(a));
        __builtin_memcpy(&b, right + i, sizeof(b));
        c = a ^ b;
        __builtin_memcpy(dst + i, &c, sizeof(c));
    }
    for (; i < length; ++i) dst[i] = (uint8_t)(left[i] ^ right[i]);
}
