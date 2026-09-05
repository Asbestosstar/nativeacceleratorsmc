#include "native_accelerator.h"

#include <string.h>

uint32_t na_abi_version(void) {
    return NA_ABI_VERSION;
}

uint64_t na_capabilities(void) {
    return NA_CAP_NATIVE | NA_CAP_PACKED_BITS | NA_CAP_QUAD_SORT | NA_CAP_IMAGE_KERNELS | NA_CAP_NOISE_KERNELS | na_platform_capabilities();
}

uint64_t na_backend_name(char *dst, uint64_t capacity) {
    const char *name = na_platform_backend_name();
    size_t n = strlen(name);
    if (capacity == 0 || dst == NULL) return (uint64_t)n;

    size_t copy = n;
    if (copy >= (size_t)capacity) copy = (size_t)capacity - 1;
    memcpy(dst, name, copy);
    dst[copy] = '\0';
    return (uint64_t)copy;
}

void na_xor_bytes(void *dst, const void *left, const void *right, uint64_t length) {
    na_platform_xor_bytes((uint8_t *)dst, (const uint8_t *)left,
                          (const uint8_t *)right, (size_t)length);
}
