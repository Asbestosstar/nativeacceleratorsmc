#include "native_accelerator.h"

#include <stddef.h>
#include <stdint.h>

#if defined(__GNUC__) || defined(__clang__)
#include <immintrin.h>

__attribute__((target("avx2")))
static void xor_avx2(uint8_t *dst, const uint8_t *a, const uint8_t *b, size_t n) {
    size_t i = 0;
    for (; i + 32 <= n; i += 32) {
        __m256i x = _mm256_loadu_si256((const __m256i *)(a + i));
        __m256i y = _mm256_loadu_si256((const __m256i *)(b + i));
        _mm256_storeu_si256((__m256i *)(dst + i), _mm256_xor_si256(x, y));
    }
    for (; i < n; ++i) dst[i] = (uint8_t)(a[i] ^ b[i]);
}

__attribute__((target("avx512f,avx512bw")))
static void xor_avx512(uint8_t *dst, const uint8_t *a, const uint8_t *b, size_t n) {
    size_t i = 0;
    for (; i + 64 <= n; i += 64) {
        __m512i x = _mm512_loadu_si512((const void *)(a + i));
        __m512i y = _mm512_loadu_si512((const void *)(b + i));
        _mm512_storeu_si512((void *)(dst + i), _mm512_xor_si512(x, y));
    }
    for (; i < n; ++i) dst[i] = (uint8_t)(a[i] ^ b[i]);
}

static int has_avx2(void) {
    __builtin_cpu_init();
    return __builtin_cpu_supports("avx2");
}

static int has_avx512(void) {
    __builtin_cpu_init();
    return __builtin_cpu_supports("avx512f") && __builtin_cpu_supports("avx512bw");
}
#else
static int has_avx2(void) { return 0; }
static int has_avx512(void) { return 0; }
#endif

uint64_t na_arch_capabilities(void) {
    uint64_t caps = 0;
    if (has_avx2()) caps |= NA_CAP_SIMD | NA_CAP_AVX2;
    if (has_avx512()) caps |= NA_CAP_SIMD | NA_CAP_AVX512F;
    return caps;
}

const char *na_arch_backend_name(void) {
    if (has_avx512()) return "amd64-avx512";
    if (has_avx2()) return "amd64-avx2";
    return "amd64-scalar";
}

void na_arch_xor_bytes(uint8_t *dst, const uint8_t *left, const uint8_t *right, size_t length) {
#if defined(__GNUC__) || defined(__clang__)
    if (has_avx512()) {
        xor_avx512(dst, left, right, length);
        return;
    }
    if (has_avx2()) {
        xor_avx2(dst, left, right, length);
        return;
    }
#endif
    for (size_t i = 0; i < length; ++i) dst[i] = (uint8_t)(left[i] ^ right[i]);
}
