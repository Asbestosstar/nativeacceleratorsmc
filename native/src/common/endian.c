#include "native_accelerator.h"
#include "na_endian.h"

#include <stddef.h>
#include <stdint.h>

uint32_t na_host_endian(void) {
    const uint16_t marker = UINT16_C(0x0102);
    const uint8_t *bytes = (const uint8_t *)&marker;
    if (bytes[0] == 0x02) return NA_ENDIAN_LITTLE;
    if (bytes[0] == 0x01) return NA_ENDIAN_BIG;
    return NA_ENDIAN_UNKNOWN;
}

uint64_t na_endian_capabilities(void) {
    switch (na_host_endian()) {
        case NA_ENDIAN_LITTLE: return NA_CAP_LITTLE_ENDIAN;
        case NA_ENDIAN_BIG: return NA_CAP_BIG_ENDIAN;
        default: return 0;
    }
}

uint16_t na_bswap16(uint16_t value) { return na_bswap16_inline(value); }
uint32_t na_bswap32(uint32_t value) { return na_bswap32_inline(value); }
uint64_t na_bswap64(uint64_t value) { return na_bswap64_inline(value); }

void na_bswap16_array(void *dst, const void *src, uint64_t count) {
    uint8_t *d = (uint8_t *)dst;
    const uint8_t *s = (const uint8_t *)src;
    for (uint64_t i = 0; i < count; ++i) {
        uint16_t v = na_read_u16_unaligned(s + i * sizeof(uint16_t));
        na_write_u16_unaligned(d + i * sizeof(uint16_t), na_bswap16_inline(v));
    }
}

void na_bswap32_array(void *dst, const void *src, uint64_t count) {
    uint8_t *d = (uint8_t *)dst;
    const uint8_t *s = (const uint8_t *)src;
    for (uint64_t i = 0; i < count; ++i) {
        uint32_t v = na_read_u32_unaligned(s + i * sizeof(uint32_t));
        na_write_u32_unaligned(d + i * sizeof(uint32_t), na_bswap32_inline(v));
    }
}

void na_bswap64_array(void *dst, const void *src, uint64_t count) {
    uint8_t *d = (uint8_t *)dst;
    const uint8_t *s = (const uint8_t *)src;
    for (uint64_t i = 0; i < count; ++i) {
        uint64_t v = na_read_u64_unaligned(s + i * sizeof(uint64_t));
        na_write_u64_unaligned(d + i * sizeof(uint64_t), na_bswap64_inline(v));
    }
}
