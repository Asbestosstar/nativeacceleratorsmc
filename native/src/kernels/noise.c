#include "native_accelerator.h"

#include <math.h>
#include <stdint.h>
#include <limits.h>

static const int8_t NA_GRADIENT[16][3] = {
    { 1, 1, 0}, {-1, 1, 0}, { 1,-1, 0}, {-1,-1, 0},
    { 1, 0, 1}, {-1, 0, 1}, { 1, 0,-1}, {-1, 0,-1},
    { 0, 1, 1}, { 0,-1, 1}, { 0, 1,-1}, { 0,-1,-1},
    { 1, 1, 0}, { 0,-1, 1}, {-1, 1, 0}, { 0,-1,-1}
};

static int na_floor_i32(double x) {
    /* Java double->int conversion clamps; preserve that behavior before floor adjustment. */
    if (x >= (double)INT_MAX) return INT_MAX;
    if (x <= (double)INT_MIN) return INT_MIN;
    int i = (int)x;
    return x < (double)i ? i - 1 : i;
}

static double na_wrap(double x, uint32_t wrap_enabled) {
    if (!wrap_enabled) return x;
    const double half_round_off = 16777215.999999998; /* Math.nextDown(16777216.0) */
    if (x >= -half_round_off && x < half_round_off) return x;
    return x - floor(x / 33554432.0 + 0.5) * 33554432.0;
}

static uint32_t na_perm(const uint8_t *perms, int x) {
    return perms[(uint32_t)x & 255u];
}

static float na_grad_dot(uint32_t hash, float x, float y, float z) {
    const int8_t *g = NA_GRADIENT[hash & 15u];
    return (float)g[0] * x + (float)g[1] * y + (float)g[2] * z;
}

static float na_smoothstep(float x) {
    return x * x * x * (x * (x * 6.0f - 15.0f) + 10.0f);
}

static float na_lerp(float t, float a, float b) { return a + t * (b - a); }
static float na_lerp2(float tx, float ty, float x00, float x10, float x01, float x11) {
    return na_lerp(ty, na_lerp(tx, x00, x10), na_lerp(tx, x01, x11));
}
static float na_lerp3(float tx, float ty, float tz,
                      float x000, float x100, float x010, float x110,
                      float x001, float x101, float x011, float x111) {
    return na_lerp(tz,
                   na_lerp2(tx, ty, x000, x100, x010, x110),
                   na_lerp2(tx, ty, x001, x101, x011, x111));
}

static float na_perlin3(const uint8_t *perms, double ox, double oy, double oz,
                        double input_x, double input_y, double input_z, uint32_t wrap_enabled) {
    const double x = na_wrap(input_x, wrap_enabled) + ox;
    const double y = na_wrap(input_y, wrap_enabled) + oy;
    const double z = na_wrap(input_z, wrap_enabled) + oz;
    const int floor_x = na_floor_i32(x);
    const int floor_y = na_floor_i32(y);
    const int floor_z = na_floor_i32(z);
    const float rx = (float)(x - floor_x);
    const float ry = (float)(y - floor_y);
    const float rz = (float)(z - floor_z);

    const uint32_t x0 = na_perm(perms, floor_x);
    const uint32_t x1 = na_perm(perms, floor_x + 1);
    const uint32_t xy00 = na_perm(perms, (int)x0 + floor_y);
    const uint32_t xy01 = na_perm(perms, (int)x0 + floor_y + 1);
    const uint32_t xy10 = na_perm(perms, (int)x1 + floor_y);
    const uint32_t xy11 = na_perm(perms, (int)x1 + floor_y + 1);

    const float d000 = na_grad_dot(na_perm(perms, (int)xy00 + floor_z), rx, ry, rz);
    const float d100 = na_grad_dot(na_perm(perms, (int)xy10 + floor_z), rx - 1.0f, ry, rz);
    const float d010 = na_grad_dot(na_perm(perms, (int)xy01 + floor_z), rx, ry - 1.0f, rz);
    const float d110 = na_grad_dot(na_perm(perms, (int)xy11 + floor_z), rx - 1.0f, ry - 1.0f, rz);
    const float d001 = na_grad_dot(na_perm(perms, (int)xy00 + floor_z + 1), rx, ry, rz - 1.0f);
    const float d101 = na_grad_dot(na_perm(perms, (int)xy10 + floor_z + 1), rx - 1.0f, ry, rz - 1.0f);
    const float d011 = na_grad_dot(na_perm(perms, (int)xy01 + floor_z + 1), rx, ry - 1.0f, rz - 1.0f);
    const float d111 = na_grad_dot(na_perm(perms, (int)xy11 + floor_z + 1), rx - 1.0f, ry - 1.0f, rz - 1.0f);
    return na_lerp3(na_smoothstep(rx), na_smoothstep(ry), na_smoothstep(rz),
                    d000, d100, d010, d110, d001, d101, d011, d111);
}

int32_t na_perlin3_batch(void *dst_f32, const void *xyz_f64, uint64_t count,
                         const void *permutations_256, double offset_x, double offset_y, double offset_z,
                         uint32_t wrap_enabled) {
    if (dst_f32 == NULL || xyz_f64 == NULL || permutations_256 == NULL) return -1;
    float *out = (float *)dst_f32;
    const double *xyz = (const double *)xyz_f64;
    const uint8_t *perms = (const uint8_t *)permutations_256;
    for (uint64_t i = 0; i < count; ++i) {
        out[i] = na_perlin3(perms, offset_x, offset_y, offset_z,
                            xyz[i * 3u], xyz[i * 3u + 1u], xyz[i * 3u + 2u], wrap_enabled);
    }
    return 0;
}

int32_t na_perlin3_volume_add(void *dst_f32,
                              uint32_t size_x, uint32_t size_y, uint32_t size_z,
                              int32_t min_block_x, int32_t min_block_y, int32_t min_block_z,
                              int32_t step_block_x, int32_t step_block_y, int32_t step_block_z,
                              double xz_scale, double y_scale, float amplitude,
                              const void *permutations_256, double offset_x, double offset_y, double offset_z,
                              uint32_t wrap_enabled) {
    if (dst_f32 == NULL || permutations_256 == NULL || size_x == 0u || size_y == 0u || size_z == 0u ||
        step_block_x <= 0 || step_block_y <= 0 || step_block_z <= 0) return -1;
    float *out = (float *)dst_f32;
    const uint8_t *perms = (const uint8_t *)permutations_256;
    uint64_t index = 0;
    /* Match DensityVolume/Noise.addToVolume ordering: Z -> X -> Y, Y contiguous. */
    for (uint32_t iz = 0; iz < size_z; ++iz) {
        const double z = (double)(min_block_z + (int32_t)iz * step_block_z) * xz_scale;
        for (uint32_t ix = 0; ix < size_x; ++ix) {
            const double x = (double)(min_block_x + (int32_t)ix * step_block_x) * xz_scale;
            for (uint32_t iy = 0; iy < size_y; ++iy) {
                const double y = (double)(min_block_y + (int32_t)iy * step_block_y) * y_scale;
                out[index] += amplitude * na_perlin3(perms, offset_x, offset_y, offset_z, x, y, z, wrap_enabled);
                ++index;
            }
        }
    }
    return 0;
}
