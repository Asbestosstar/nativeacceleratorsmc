#include "native_accelerator.h"

uint64_t na_arch_capabilities(void) {
    return 0;
}

const char *na_arch_backend_name(void) {
    return "generic-c";
}

void na_arch_xor_bytes(uint8_t *dst, const uint8_t *left, const uint8_t *right, size_t length) {
    for (size_t i = 0; i < length; ++i) {
        dst[i] = (uint8_t)(left[i] ^ right[i]);
    }
}
