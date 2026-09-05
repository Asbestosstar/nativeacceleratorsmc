#ifndef NATIVE_ACCELERATOR_H
#define NATIVE_ACCELERATOR_H

#include <stddef.h>
#include <stdint.h>
#include "na_endian.h"

#if defined(_WIN32)
#  define NA_EXPORT __declspec(dllexport)
#else
#  define NA_EXPORT __attribute__((visibility("default")))
#endif

#define NA_ABI_VERSION 3u

#define NA_CAP_NATIVE       (UINT64_C(1) << 0)
#define NA_CAP_SIMD         (UINT64_C(1) << 1)
#define NA_CAP_AVX2         (UINT64_C(1) << 2)
#define NA_CAP_AVX512F      (UINT64_C(1) << 3)
#define NA_CAP_SPARC_VIS    (UINT64_C(1) << 4)
#define NA_CAP_SPARC_VIS2   (UINT64_C(1) << 5)
#define NA_CAP_SPARC_VIS3   (UINT64_C(1) << 6)
#define NA_CAP_DAX_LIBRARY  (UINT64_C(1) << 7)
#define NA_CAP_DAX_DEVICE     (UINT64_C(1) << 8)
#define NA_CAP_POSIX          (UINT64_C(1) << 9)
#define NA_CAP_WINDOWS        (UINT64_C(1) << 10)
#define NA_CAP_IA64           (UINT64_C(1) << 11)
#define NA_CAP_BIG_ENDIAN     (UINT64_C(1) << 12)
#define NA_CAP_LITTLE_ENDIAN  (UINT64_C(1) << 13)
#define NA_CAP_BIENDIAN_ARCH  (UINT64_C(1) << 14)
#define NA_CAP_PPC32          (UINT64_C(1) << 15)
#define NA_CAP_BSD            (UINT64_C(1) << 16)
#define NA_CAP_MACOS          (UINT64_C(1) << 17)
#define NA_CAP_FREEBSD        (UINT64_C(1) << 18)
#define NA_CAP_NETBSD         (UINT64_C(1) << 19)
#define NA_CAP_OPENBSD        (UINT64_C(1) << 20)
#define NA_CAP_LINUX          (UINT64_C(1) << 21)
#define NA_CAP_PACKED_BITS    (UINT64_C(1) << 22)
#define NA_CAP_QUAD_SORT      (UINT64_C(1) << 23)
#define NA_CAP_IMAGE_KERNELS  (UINT64_C(1) << 24)
#define NA_CAP_NOISE_KERNELS  (UINT64_C(1) << 25)

#ifdef __cplusplus
extern "C" {
#endif

NA_EXPORT uint32_t na_abi_version(void);
NA_EXPORT uint64_t na_capabilities(void);
NA_EXPORT uint64_t na_backend_name(char *dst, uint64_t capacity);
NA_EXPORT void na_xor_bytes(void *dst, const void *left, const void *right, uint64_t length);

/* Minecraft-oriented bulk kernels. Return 0 on success unless documented otherwise. */
NA_EXPORT int32_t na_packed_bits_unpack_u32(void *dst, const void *src, uint32_t bits, uint64_t count);
NA_EXPORT int32_t na_packed_bits_pack_u32(void *dst, const void *src, uint32_t bits, uint64_t count);
NA_EXPORT int32_t na_packed_bits_repack(void *dst, uint32_t dst_bits, const void *src, uint32_t src_bits, uint64_t count);
NA_EXPORT int32_t na_simple_bits_unpack_u32(void *dst, const void *src, uint32_t bits, uint64_t count);
NA_EXPORT int32_t na_simple_bits_pack_u32(void *dst, const void *src, uint32_t bits, uint64_t count);
NA_EXPORT int32_t na_simple_bits_repack(void *dst, uint32_t dst_bits, const void *src, uint32_t src_bits, uint64_t count);

NA_EXPORT int32_t na_decode_quad_centroids(void *dst_xyz, const void *vertex_data, uint64_t vertex_count,
                                           uint32_t vertex_stride, uint32_t position_offset);
NA_EXPORT int32_t na_sort_quad_indices_distance(void *dst_quad_indices, const void *centroids_xyz, uint64_t quad_count,
                                                float origin_x, float origin_y, float origin_z);
NA_EXPORT int32_t na_sort_quads_distance_write_indices(void *dst_indices, uint32_t index_bytes,
                                                       const void *vertex_data, uint64_t vertex_count,
                                                       uint32_t vertex_stride, uint32_t position_offset,
                                                       float origin_x, float origin_y, float origin_z);

NA_EXPORT void na_swizzle_argb_abgr_u32(void *dst, const void *src, uint64_t count);
NA_EXPORT int32_t na_image_fill_u32_rect(void *pixels, uint32_t image_width, uint32_t image_height,
                                         int32_t x, int32_t y, uint32_t width, uint32_t height, uint32_t pixel);
NA_EXPORT int32_t na_image_copy_u32_rect(void *dst_pixels, uint32_t dst_width, uint32_t dst_height,
                                         int32_t dst_x, int32_t dst_y,
                                         const void *src_pixels, uint32_t src_width, uint32_t src_height,
                                         int32_t src_x, int32_t src_y, uint32_t width, uint32_t height,
                                         uint32_t swap_x, uint32_t swap_y);

NA_EXPORT int32_t na_perlin3_batch(void *dst_f32, const void *xyz_f64, uint64_t count,
                                   const void *permutations_256, double offset_x, double offset_y, double offset_z,
                                   uint32_t wrap_enabled);
NA_EXPORT int32_t na_perlin3_volume_add(void *dst_f32,
                                        uint32_t size_x, uint32_t size_y, uint32_t size_z,
                                        int32_t min_block_x, int32_t min_block_y, int32_t min_block_z,
                                        int32_t step_block_x, int32_t step_block_y, int32_t step_block_z,
                                        double xz_scale, double y_scale, float amplitude,
                                        const void *permutations_256, double offset_x, double offset_y, double offset_z,
                                        uint32_t wrap_enabled);

/* Endian ABI. Host order is runtime-reported even on bi-endian architectures. */
NA_EXPORT uint32_t na_host_endian(void);
NA_EXPORT uint16_t na_bswap16(uint16_t value);
NA_EXPORT uint32_t na_bswap32(uint32_t value);
NA_EXPORT uint64_t na_bswap64(uint64_t value);
NA_EXPORT void na_bswap16_array(void *dst, const void *src, uint64_t count);
NA_EXPORT void na_bswap32_array(void *dst, const void *src, uint64_t count);
NA_EXPORT void na_bswap64_array(void *dst, const void *src, uint64_t count);

uint64_t na_endian_capabilities(void);

/* Combined platform hooks implemented by common/platform_dispatch.c. */
uint64_t na_platform_capabilities(void);
const char *na_platform_backend_name(void);
void na_platform_xor_bytes(uint8_t *dst, const uint8_t *left, const uint8_t *right, size_t length);

/* Architecture hooks: exactly one arch implementation is linked. */
uint64_t na_arch_capabilities(void);
const char *na_arch_backend_name(void);
void na_arch_xor_bytes(uint8_t *dst, const uint8_t *left, const uint8_t *right, size_t length);

/* Operating-system hooks: exactly one OS implementation is linked. */
uint64_t na_os_capabilities(void);
const char *na_os_backend_name(void);

/* Shared POSIX hooks, linked only for Unix-like targets. */
uint64_t na_posix_capabilities(void);
uint64_t na_bsd_capabilities(void);
size_t na_posix_page_size(void);
uint64_t na_posix_monotonic_nanos(void);

#ifdef __cplusplus
}
#endif

#endif
