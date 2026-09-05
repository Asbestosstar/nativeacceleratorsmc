#include "native_accelerator.h"

#include <stdint.h>
#include <string.h>

static uint32_t na_swap_rb(uint32_t value) {
    return (value & UINT32_C(0xFF00FF00)) |
           ((value & UINT32_C(0x00FF0000)) >> 16) |
           ((value & UINT32_C(0x000000FF)) << 16);
}

void na_swizzle_argb_abgr_u32(void *dst, const void *src, uint64_t count) {
    uint8_t *out = (uint8_t *)dst;
    const uint8_t *in = (const uint8_t *)src;
    for (uint64_t i = 0; i < count; ++i) {
        uint32_t v;
        memcpy(&v, in + i * 4u, 4u);
        v = na_swap_rb(v);
        memcpy(out + i * 4u, &v, 4u);
    }
}

int32_t na_image_fill_u32_rect(void *pixels, uint32_t image_width, uint32_t image_height,
                               int32_t x, int32_t y, uint32_t width, uint32_t height, uint32_t pixel) {
    if (pixels == NULL || x < 0 || y < 0) return -1;
    if ((uint64_t)(uint32_t)x + width > image_width || (uint64_t)(uint32_t)y + height > image_height) return -2;
    uint8_t *base = (uint8_t *)pixels;
    for (uint32_t row = 0; row < height; ++row) {
        uint8_t *p = base + (((uint64_t)(uint32_t)y + row) * image_width + (uint32_t)x) * 4u;
        for (uint32_t col = 0; col < width; ++col) memcpy(p + col * 4u, &pixel, 4u);
    }
    return 0;
}

int32_t na_image_copy_u32_rect(void *dst_pixels, uint32_t dst_width, uint32_t dst_height,
                               int32_t dst_x, int32_t dst_y,
                               const void *src_pixels, uint32_t src_width, uint32_t src_height,
                               int32_t src_x, int32_t src_y,
                               uint32_t width, uint32_t height, uint32_t swap_x, uint32_t swap_y) {
    if (dst_pixels == NULL || src_pixels == NULL || dst_x < 0 || dst_y < 0 || src_x < 0 || src_y < 0) return -1;
    if ((uint64_t)(uint32_t)dst_x + width > dst_width || (uint64_t)(uint32_t)dst_y + height > dst_height ||
        (uint64_t)(uint32_t)src_x + width > src_width || (uint64_t)(uint32_t)src_y + height > src_height) return -2;

    uint8_t *dst = (uint8_t *)dst_pixels;
    const uint8_t *src = (const uint8_t *)src_pixels;
    /* Deliberately preserve Minecraft's source-then-write iteration semantics for overlap. */
    for (uint32_t row = 0; row < height; ++row) {
        for (uint32_t col = 0; col < width; ++col) {
            const uint32_t dx = swap_x ? (width - 1u - col) : col;
            const uint32_t dy = swap_y ? (height - 1u - row) : row;
            uint32_t pixel;
            const uint64_t so = (((uint64_t)(uint32_t)src_y + row) * src_width + (uint32_t)src_x + col) * 4u;
            const uint64_t doff = (((uint64_t)(uint32_t)dst_y + dy) * dst_width + (uint32_t)dst_x + dx) * 4u;
            memcpy(&pixel, src + so, 4u);
            memcpy(dst + doff, &pixel, 4u);
        }
    }
    return 0;
}
