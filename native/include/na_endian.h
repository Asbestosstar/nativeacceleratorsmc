#ifndef NA_ENDIAN_H
#define NA_ENDIAN_H

#include <stdint.h>
#include <string.h>
#if defined(_MSC_VER)
#include <stdlib.h>
#include <intrin.h>
#endif

#define NA_ENDIAN_UNKNOWN 0u
#define NA_ENDIAN_LITTLE  1u
#define NA_ENDIAN_BIG     2u

static inline uint16_t na_bswap16_inline(uint16_t v) {
#if defined(__GNUC__) || defined(__clang__)
    return __builtin_bswap16(v);
#elif defined(_MSC_VER)
    return _byteswap_ushort(v);
#else
    return (uint16_t)((v << 8) | (v >> 8));
#endif
}

static inline uint32_t na_bswap32_inline(uint32_t v) {
#if defined(__GNUC__) || defined(__clang__)
    return __builtin_bswap32(v);
#elif defined(_MSC_VER)
    return _byteswap_ulong(v);
#else
    return ((v & UINT32_C(0x000000ff)) << 24) |
           ((v & UINT32_C(0x0000ff00)) << 8)  |
           ((v & UINT32_C(0x00ff0000)) >> 8)  |
           ((v & UINT32_C(0xff000000)) >> 24);
#endif
}

static inline uint64_t na_bswap64_inline(uint64_t v) {
#if defined(__GNUC__) || defined(__clang__)
    return __builtin_bswap64(v);
#elif defined(_MSC_VER)
    return _byteswap_uint64(v);
#else
    return ((v & UINT64_C(0x00000000000000ff)) << 56) |
           ((v & UINT64_C(0x000000000000ff00)) << 40) |
           ((v & UINT64_C(0x0000000000ff0000)) << 24) |
           ((v & UINT64_C(0x00000000ff000000)) << 8)  |
           ((v & UINT64_C(0x000000ff00000000)) >> 8)  |
           ((v & UINT64_C(0x0000ff0000000000)) >> 24) |
           ((v & UINT64_C(0x00ff000000000000)) >> 40) |
           ((v & UINT64_C(0xff00000000000000)) >> 56);
#endif
}

static inline uint16_t na_read_u16_unaligned(const void *p) {
    uint16_t v;
    memcpy(&v, p, sizeof(v));
    return v;
}

static inline uint32_t na_read_u32_unaligned(const void *p) {
    uint32_t v;
    memcpy(&v, p, sizeof(v));
    return v;
}

static inline uint64_t na_read_u64_unaligned(const void *p) {
    uint64_t v;
    memcpy(&v, p, sizeof(v));
    return v;
}

static inline void na_write_u16_unaligned(void *p, uint16_t v) { memcpy(p, &v, sizeof(v)); }
static inline void na_write_u32_unaligned(void *p, uint32_t v) { memcpy(p, &v, sizeof(v)); }
static inline void na_write_u64_unaligned(void *p, uint64_t v) { memcpy(p, &v, sizeof(v)); }

static inline int na_runtime_is_little_endian(void) {
    const uint16_t marker = UINT16_C(0x0102);
    uint8_t first;
    memcpy(&first, &marker, 1);
    return first == 0x02;
}

static inline uint16_t na_le16_to_host(uint16_t v) {
#if defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_BIG_ENDIAN__)
    return na_bswap16_inline(v);
#elif defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__)
    return v;
#else
    return na_runtime_is_little_endian() ? v : na_bswap16_inline(v);
#endif
}

static inline uint32_t na_le32_to_host(uint32_t v) {
#if defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_BIG_ENDIAN__)
    return na_bswap32_inline(v);
#elif defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__)
    return v;
#else
    return na_runtime_is_little_endian() ? v : na_bswap32_inline(v);
#endif
}

static inline uint64_t na_le64_to_host(uint64_t v) {
#if defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_BIG_ENDIAN__)
    return na_bswap64_inline(v);
#elif defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__)
    return v;
#else
    return na_runtime_is_little_endian() ? v : na_bswap64_inline(v);
#endif
}

static inline uint16_t na_be16_to_host(uint16_t v) {
#if defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__)
    return na_bswap16_inline(v);
#elif defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_BIG_ENDIAN__)
    return v;
#else
    return na_runtime_is_little_endian() ? na_bswap16_inline(v) : v;
#endif
}

static inline uint32_t na_be32_to_host(uint32_t v) {
#if defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__)
    return na_bswap32_inline(v);
#elif defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_BIG_ENDIAN__)
    return v;
#else
    return na_runtime_is_little_endian() ? na_bswap32_inline(v) : v;
#endif
}

static inline uint64_t na_be64_to_host(uint64_t v) {
#if defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__)
    return na_bswap64_inline(v);
#elif defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_BIG_ENDIAN__)
    return v;
#else
    return na_runtime_is_little_endian() ? na_bswap64_inline(v) : v;
#endif
}

#define na_host_to_le16(v) na_le16_to_host(v)
#define na_host_to_le32(v) na_le32_to_host(v)
#define na_host_to_le64(v) na_le64_to_host(v)
#define na_host_to_be16(v) na_be16_to_host(v)
#define na_host_to_be32(v) na_be32_to_host(v)
#define na_host_to_be64(v) na_be64_to_host(v)

#endif
