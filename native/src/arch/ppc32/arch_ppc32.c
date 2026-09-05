#include "native_accelerator.h"

#include <stddef.h>
#include <stdint.h>

/*
 * 32-bit PowerPC backend.
 *
 * This directory intentionally represents the architecture rather than an OS:
 * the same core can be paired with classic/macOS Darwin, Linux or BSD.
 * PPC32 is treated as endian-variable because real PowerPC deployments exist
 * with different byte orders.  Optimized AltiVec/G4 kernels can be added here
 * without changing the Panama/JNI ABI or any OS backend.
 */
uint64_t na_arch_capabilities(void) {
    return NA_CAP_PPC32 | NA_CAP_BIENDIAN_ARCH;
}

const char *na_arch_backend_name(void) {
    return "ppc32-scalar";
}

void na_arch_xor_bytes(uint8_t *dst, const uint8_t *left, const uint8_t *right, size_t length) {
    size_t i = 0;
    for (; i + sizeof(uint32_t) <= length; i += sizeof(uint32_t)) {
        uint32_t a, b, c;
        __builtin_memcpy(&a, left + i, sizeof(a));
        __builtin_memcpy(&b, right + i, sizeof(b));
        c = a ^ b;
        __builtin_memcpy(dst + i, &c, sizeof(c));
    }
    for (; i < length; ++i) {
        dst[i] = (uint8_t)(left[i] ^ right[i]);
    }
}
