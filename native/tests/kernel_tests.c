#include "native_accelerator.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

/*
 * Exhaustive simple-layout coverage. The kernels were rewritten to iterate one 64-bit cell at a time
 * (removing a per-element integer division), which introduced new edge cases that the original single
 * bits=5 case could not catch: empty input, a full-width bits=32 value, a count that exactly fills its
 * final cell, and a final cell that is only partially filled.
 */
static int simple_round_trip(uint32_t bits, uint32_t count) {
    uint32_t values[600], unpacked[600];
    uint64_t packed[600];
    const uint32_t vpl = 64u / bits;
    const uint32_t cells = (count + vpl - 1u) / vpl;
    const uint64_t mask = ((uint64_t)1 << bits) - 1u;

    for (uint32_t i = 0; i < count; ++i) {
        values[i] = (uint32_t)(((uint64_t)i * 2654435761u + 12345u) & mask);
    }

    memset(packed, 0, sizeof(packed));
    if (na_simple_bits_pack_u32(packed, values, bits, count) != 0) return 0;

    /* Beyond the values, every cell bit must be zero: packing must not leave stale bits set. */
    if (count < 600) {
        memset(unpacked, 0xAA, sizeof(unpacked));
        if (na_simple_bits_unpack_u32(unpacked, packed, bits, count) != 0) return 0;
        if (memcmp(values, unpacked, count * sizeof(uint32_t)) != 0) return 0;
    }

    /* A value that does not fit must be rejected, not silently truncated. */
    if (bits < 32) {
        uint32_t bad[1] = {bits == 0 ? 1u : (uint32_t)(mask + 1u)};
        if (na_simple_bits_pack_u32(packed, bad, bits, 1) != -2) return 0;
    }

    (void)cells;
    return 1;
}

static int test_simple_bits(void) {
    const uint32_t widths[] = {1, 2, 3, 4, 5, 7, 8, 9, 16, 31, 32};
    const uint32_t counts[] = {0, 1, 2, 63, 64, 65, 127, 128, 129, 256, 257, 511, 512, 599};

    for (size_t w = 0; w < sizeof(widths) / sizeof(widths[0]); ++w) {
        for (size_t c = 0; c < sizeof(counts) / sizeof(counts[0]); ++c) {
            if (!simple_round_trip(widths[w], counts[c])) {
                fprintf(stderr, "simple bits round trip failed bits=%u count=%u\n", widths[w], counts[c]);
                return 0;
            }
        }
    }

    /* "Invalid" and empty inputs must be rejected cleanly. */
    uint32_t one[1] = {1};
    uint64_t cell[1] = {0};
    if (na_simple_bits_pack_u32(cell, one, 0, 1) != -1) return 0;
    if (na_simple_bits_pack_u32(cell, one, 33, 1) != -1) return 0;
    if (na_simple_bits_unpack_u32(one, cell, 0, 1) != -1) return 0;
    if (na_simple_bits_unpack_u32(one, cell, 33, 1) != -1) return 0;
    if (na_simple_bits_pack_u32(NULL, one, 5, 1) != -1) return 0;
    if (na_simple_bits_unpack_u32(NULL, cell, 5, 1) != -1) return 0;

    return 1;
}

static int test_simple_repack(void) {
    const uint32_t src_bits = 5, dst_bits = 9, count = 300;
    uint32_t values[300], unpacked[300];
    uint64_t src[300], dst[300];
    const uint64_t src_mask = ((uint64_t)1 << src_bits) - 1u;

    for (uint32_t i = 0; i < count; ++i) values[i] = (uint32_t)((i * 7u + 3u) & src_mask);
    if (na_simple_bits_pack_u32(src, values, src_bits, count) != 0) return 0;
    if (na_simple_bits_repack(dst, dst_bits, src, src_bits, count) != 0) return 0;
    if (na_simple_bits_unpack_u32(unpacked, dst, dst_bits, count) != 0) return 0;
    if (memcmp(values, unpacked, count * sizeof(uint32_t)) != 0) return 0;

    /* Narrowing to a width that cannot hold the source values must be reported, not truncated. */
    if (na_simple_bits_repack(dst, 2, src, src_bits, count) != -2) return 0;

    return 1;
}

static int test_dense_bits(void) {
    enum { COUNT = 257, BITS = 13 };
    uint32_t values[COUNT], unpacked[COUNT];
    uint64_t packed[(COUNT * BITS + 63) / 64];
    for (uint32_t i = 0; i < COUNT; ++i) values[i] = (i * 73u + 5u) & ((1u << BITS) - 1u);
    if (na_packed_bits_pack_u32(packed, values, BITS, COUNT) != 0) return 0;
    memset(unpacked, 0, sizeof(unpacked));
    if (na_packed_bits_unpack_u32(unpacked, packed, BITS, COUNT) != 0) return 0;
    return memcmp(values, unpacked, sizeof(values)) == 0;
}

static int test_quads(void) {
    enum { STRIDE = 20, VERTICES = 12 };
    uint8_t vertex_data[STRIDE * VERTICES];
    memset(vertex_data, 0, sizeof(vertex_data));
    const float centers[3] = {1.0f, 3.0f, 2.0f};
    for (uint32_t q = 0; q < 3; ++q) {
        for (uint32_t v = 0; v < 4; ++v) {
            float xyz[3] = {centers[q] + ((v == 0 || v == 3) ? -0.5f : 0.5f), 0.0f,
                            (v < 2) ? -0.5f : 0.5f};
            memcpy(vertex_data + (q * 4u + v) * STRIDE, xyz, sizeof(xyz));
        }
    }
    uint16_t indices[18];
    if (na_sort_quads_distance_write_indices(indices, 2, vertex_data, VERTICES, STRIDE, 0, 0, 0, 0) != 0) return 0;
    const uint16_t expected[18] = {4,5,6,6,7,4, 8,9,10,10,11,8, 0,1,2,2,3,0};
    return memcmp(indices, expected, sizeof(expected)) == 0;
}

static int test_image(void) {
    uint32_t pixels[16];
    for (uint32_t i = 0; i < 16; ++i) pixels[i] = i;
    if (na_image_fill_u32_rect(pixels, 4, 4, 1, 1, 2, 2, UINT32_C(0xAABBCCDD)) != 0) return 0;
    return pixels[5] == UINT32_C(0xAABBCCDD) && pixels[6] == UINT32_C(0xAABBCCDD) &&
           pixels[9] == UINT32_C(0xAABBCCDD) && pixels[10] == UINT32_C(0xAABBCCDD);
}

static int test_perlin(void) {
    uint8_t perms[256];
    for (uint32_t i = 0; i < 256; ++i) perms[i] = (uint8_t)i;
    const double xyz[3] = {0.1, 0.2, 0.3};
    float value = 0.0f;
    if (na_perlin3_batch(&value, xyz, 1, perms, 1.25, 2.5, 3.75, 1) != 0) return 0;
    /* Fixed regression value for identity permutation; tolerance allows normal compiler FP variation. */
    return fabsf(value - (-0.11258017f)) < 1.0e-5f;
}

int main(void) {
    int ok = 1;
    ok &= test_simple_bits();
    ok &= test_simple_repack();
    ok &= test_dense_bits();
    ok &= test_quads();
    ok &= test_image();
    ok &= test_perlin();
    if (!ok) {
        fputs("Native Accelerator kernel test failed\n", stderr);
        return 1;
    }
    puts("Native Accelerator kernel tests passed");
    return 0;
}
