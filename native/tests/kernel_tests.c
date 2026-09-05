#include "native_accelerator.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static int test_simple_bits(void) {
    enum { COUNT = 257, BITS = 5 };
    uint32_t values[COUNT], unpacked[COUNT];
    const uint32_t vpl = 64u / BITS;
    uint64_t packed[(COUNT + vpl - 1u) / vpl];
    for (uint32_t i = 0; i < COUNT; ++i) values[i] = (i * 7u) & 31u;
    if (na_simple_bits_pack_u32(packed, values, BITS, COUNT) != 0) return 0;
    memset(unpacked, 0, sizeof(unpacked));
    if (na_simple_bits_unpack_u32(unpacked, packed, BITS, COUNT) != 0) return 0;
    return memcmp(values, unpacked, sizeof(values)) == 0;
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
