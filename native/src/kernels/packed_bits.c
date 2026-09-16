#include "native_accelerator.h"

#include <stdint.h>
#include <string.h>

static uint64_t na_load_u64(const uint8_t *p) {
    uint64_t v;
    memcpy(&v, p, sizeof(v));
    return v;
}

static void na_store_u64(uint8_t *p, uint64_t v) {
    memcpy(p, &v, sizeof(v));
}

static uint32_t na_load_u32(const uint8_t *p) {
    uint32_t v;
    memcpy(&v, p, sizeof(v));
    return v;
}

static void na_store_u32(uint8_t *p, uint32_t v) {
    memcpy(p, &v, sizeof(v));
}

static int na_valid_bits(uint32_t bits) {
    return bits >= 1u && bits <= 32u;
}

static uint64_t na_mask_for_bits(uint32_t bits) {
    return (UINT64_C(1) << bits) - UINT64_C(1);
}

/*
 * Dense bit stream used by net.minecraft.util.datafix.fixes.PackedBitStorage.
 * Values may cross 64-bit cell boundaries.
 */
int32_t na_packed_bits_unpack_u32(void *dst, const void *src, uint32_t bits, uint64_t count) {
    if (dst == NULL || src == NULL || !na_valid_bits(bits)) return -1;
    uint8_t *out = (uint8_t *)dst;
    const uint8_t *in = (const uint8_t *)src;
    const uint64_t mask = na_mask_for_bits(bits);

    for (uint64_t i = 0; i < count; ++i) {
        const uint64_t position = i * (uint64_t)bits;
        const uint64_t start_cell = position >> 6;
        const uint32_t start_bit = (uint32_t)(position & 63u);
        uint64_t value = na_load_u64(in + start_cell * 8u) >> start_bit;
        if (start_bit + bits > 64u) {
            value |= na_load_u64(in + (start_cell + 1u) * 8u) << (64u - start_bit);
        }
        na_store_u32(out + i * 4u, (uint32_t)(value & mask));
    }
    return 0;
}

int32_t na_packed_bits_pack_u32(void *dst, const void *src, uint32_t bits, uint64_t count) {
    if (dst == NULL || src == NULL || !na_valid_bits(bits)) return -1;
    uint8_t *out = (uint8_t *)dst;
    const uint8_t *in = (const uint8_t *)src;
    const uint64_t mask = na_mask_for_bits(bits);
    const uint64_t total_bits = count * (uint64_t)bits;
    const uint64_t cells = (total_bits + 63u) >> 6;
    memset(out, 0, (size_t)cells * 8u);

    for (uint64_t i = 0; i < count; ++i) {
        const uint64_t value = (uint64_t)na_load_u32(in + i * 4u) & mask;
        const uint64_t position = i * (uint64_t)bits;
        const uint64_t start_cell = position >> 6;
        const uint32_t start_bit = (uint32_t)(position & 63u);
        uint64_t cell = na_load_u64(out + start_cell * 8u);
        cell |= value << start_bit;
        na_store_u64(out + start_cell * 8u, cell);
        if (start_bit + bits > 64u) {
            uint64_t next = na_load_u64(out + (start_cell + 1u) * 8u);
            next |= value >> (64u - start_bit);
            na_store_u64(out + (start_cell + 1u) * 8u, next);
        }
    }
    return 0;
}

int32_t na_packed_bits_repack(void *dst, uint32_t dst_bits, const void *src, uint32_t src_bits, uint64_t count) {
    if (dst == NULL || src == NULL || !na_valid_bits(src_bits) || !na_valid_bits(dst_bits)) return -1;
    uint8_t *out = (uint8_t *)dst;
    const uint8_t *in = (const uint8_t *)src;
    const uint64_t src_mask = na_mask_for_bits(src_bits);
    const uint64_t dst_mask = na_mask_for_bits(dst_bits);
    const uint64_t dst_cells = (count * (uint64_t)dst_bits + 63u) >> 6;
    memset(out, 0, (size_t)dst_cells * 8u);

    for (uint64_t i = 0; i < count; ++i) {
        const uint64_t src_pos = i * (uint64_t)src_bits;
        const uint64_t src_cell = src_pos >> 6;
        const uint32_t src_shift = (uint32_t)(src_pos & 63u);
        uint64_t value = na_load_u64(in + src_cell * 8u) >> src_shift;
        if (src_shift + src_bits > 64u) {
            value |= na_load_u64(in + (src_cell + 1u) * 8u) << (64u - src_shift);
        }
        value &= src_mask;
        if (value > dst_mask) return -2;

        const uint64_t dst_pos = i * (uint64_t)dst_bits;
        const uint64_t dst_cell = dst_pos >> 6;
        const uint32_t dst_shift = (uint32_t)(dst_pos & 63u);
        uint64_t cell = na_load_u64(out + dst_cell * 8u);
        cell |= value << dst_shift;
        na_store_u64(out + dst_cell * 8u, cell);
        if (dst_shift + dst_bits > 64u) {
            uint64_t next = na_load_u64(out + (dst_cell + 1u) * 8u);
            next |= value >> (64u - dst_shift);
            na_store_u64(out + (dst_cell + 1u) * 8u, next);
        }
    }
    return 0;
}

/*
 * Runtime SimpleBitStorage layout. Each 64-bit cell contains floor(64/bits)
 * complete values; values never cross cell boundaries.
 *
 * These kernels iterate one 64-bit cell at a time, exactly like the vanilla
 * Java loop they replace. An earlier revision derived the cell index per value
 * with `i / values_per_long`; that 64-bit integer division ran once per element
 * and made the native path several times slower than the Java loop for every
 * size, so the acceleration gate could never be reached. Shifting a register
 * across the values within a cell removes the division from the hot loop.
 */
int32_t na_simple_bits_unpack_u32(void *dst, const void *src, uint32_t bits, uint64_t count) {
    if (dst == NULL || src == NULL || !na_valid_bits(bits)) return -1;
    uint8_t *out = (uint8_t *)dst;
    const uint8_t *in = (const uint8_t *)src;
    const uint32_t values_per_long = 64u / bits;
    const uint64_t mask = na_mask_for_bits(bits);
    if (count == 0u) return 0;

    const uint64_t cells = (count + values_per_long - 1u) / values_per_long;
    uint64_t index = 0;

    for (uint64_t cell_index = 0; cell_index + 1u < cells; ++cell_index) {
        uint64_t cell = na_load_u64(in + cell_index * 8u);
        for (uint32_t i = 0; i < values_per_long; ++i) {
            na_store_u32(out + index * 4u, (uint32_t)(cell & mask));
            cell >>= bits;
            ++index;
        }
    }

    /* Final cell holds only the remaining values, which may be a full cell. */
    const uint64_t remaining = count - index;
    uint64_t cell = na_load_u64(in + (cells - 1u) * 8u);
    for (uint64_t i = 0; i < remaining; ++i) {
        na_store_u32(out + index * 4u, (uint32_t)(cell & mask));
        cell >>= bits;
        ++index;
    }
    return 0;
}

int32_t na_simple_bits_pack_u32(void *dst, const void *src, uint32_t bits, uint64_t count) {
    if (dst == NULL || src == NULL || !na_valid_bits(bits)) return -1;
    uint8_t *out = (uint8_t *)dst;
    const uint8_t *in = (const uint8_t *)src;
    const uint32_t values_per_long = 64u / bits;
    const uint64_t mask = na_mask_for_bits(bits);
    const uint64_t cells = (count + values_per_long - 1u) / values_per_long;
    memset(out, 0, (size_t)cells * 8u);

    uint64_t index = 0;
    for (uint64_t cell_index = 0; cell_index < cells; ++cell_index) {
        const uint64_t available = count - index;
        const uint32_t n = (available < values_per_long) ? (uint32_t)available : values_per_long;
        uint64_t cell = 0;
        uint32_t shift = 0;
        for (uint32_t i = 0; i < n; ++i) {
            const uint32_t value = na_load_u32(in + index * 4u);
            if ((uint64_t)value > mask) return -2;
            cell |= ((uint64_t)value & mask) << shift;
            shift += bits;
            ++index;
        }
        na_store_u64(out + cell_index * 8u, cell);
    }
    return 0;
}

int32_t na_simple_bits_repack(void *dst, uint32_t dst_bits, const void *src, uint32_t src_bits, uint64_t count) {
    if (dst == NULL || src == NULL || !na_valid_bits(src_bits) || !na_valid_bits(dst_bits)) return -1;
    uint8_t *out = (uint8_t *)dst;
    const uint8_t *in = (const uint8_t *)src;
    const uint32_t src_vpl = 64u / src_bits;
    const uint32_t dst_vpl = 64u / dst_bits;
    const uint64_t src_mask = na_mask_for_bits(src_bits);
    const uint64_t dst_mask = na_mask_for_bits(dst_bits);
    const uint64_t dst_cells = (count + dst_vpl - 1u) / dst_vpl;
    memset(out, 0, (size_t)dst_cells * 8u);
    if (count == 0u) return 0;

    uint64_t src_cell_index = 0;
    uint64_t src_cell = na_load_u64(in);
    uint32_t src_within = 0;
    uint64_t index = 0;

    for (uint64_t dst_cell_index = 0; dst_cell_index < dst_cells; ++dst_cell_index) {
        const uint64_t available = count - index;
        const uint32_t n = (available < dst_vpl) ? (uint32_t)available : dst_vpl;
        uint64_t packed = 0;
        uint32_t shift = 0;
        for (uint32_t i = 0; i < n; ++i) {
            if (src_within == src_vpl) {
                ++src_cell_index;
                src_cell = na_load_u64(in + src_cell_index * 8u);
                src_within = 0;
            }
            const uint64_t value = (src_cell >> (src_within * src_bits)) & src_mask;
            if (value > dst_mask) return -2;
            packed |= value << shift;
            shift += dst_bits;
            ++src_within;
            ++index;
        }
        na_store_u64(out + dst_cell_index * 8u, packed);
    }
    return 0;
}
