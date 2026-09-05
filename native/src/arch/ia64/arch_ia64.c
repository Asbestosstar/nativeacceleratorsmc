#include "native_accelerator.h"

#include <stddef.h>
#include <stdint.h>

/*
 * IA-64 / Itanium placeholder.
 *
 * Itanium is kept explicit instead of falling into generic-c so the project
 * can plan for EPIC-specific scheduling, ABI quirks and possible
 * platform-specific compiler intrinsics without disturbing other backends.
 * The initial implementation is intentionally conservative scalar C.
 */
uint64_t na_arch_capabilities(void) {
    return NA_CAP_IA64;
}

const char *na_arch_backend_name(void) {
    return "ia64-scalar";
}

void na_arch_xor_bytes(uint8_t *dst, const uint8_t *left, const uint8_t *right, size_t length) {
    for (size_t i = 0; i < length; ++i) {
        dst[i] = (uint8_t)(left[i] ^ right[i]);
    }
}
