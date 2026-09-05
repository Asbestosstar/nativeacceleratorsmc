#include "native_accelerator.h"

#include <stdio.h>

uint64_t na_platform_capabilities(void) {
    return na_arch_capabilities() | na_os_capabilities() | na_endian_capabilities();
}

const char *na_platform_backend_name(void) {
    static char name[128];
    (void)snprintf(name, sizeof(name), "%s-%s", na_os_backend_name(), na_arch_backend_name());
    return name;
}

void na_platform_xor_bytes(uint8_t *dst, const uint8_t *left, const uint8_t *right, size_t length) {
    na_arch_xor_bytes(dst, left, right, length);
}
