#include "native_accelerator.h"

#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct na_quad_key {
    float distance;
    uint32_t index;
} na_quad_key;

static float na_load_f32(const uint8_t *p) {
    float v;
    memcpy(&v, p, sizeof(v));
    return v;
}

static void na_store_f32(uint8_t *p, float v) {
    memcpy(p, &v, sizeof(v));
}

static int na_key_before(const na_quad_key *a, const na_quad_key *b) {
    const int a_nan = isnan(a->distance) != 0;
    const int b_nan = isnan(b->distance) != 0;
    if (a_nan != b_nan) return a_nan; /* Float.compare descending: NaN sorts first. */
    if (a->distance > b->distance) return 1;
    if (a->distance < b->distance) return 0;
    return a->index < b->index; /* stable tie order */
}

static void na_merge_sort_keys(na_quad_key *items, na_quad_key *tmp, size_t n) {
    for (size_t width = 1; width < n; width *= 2) {
        for (size_t left = 0; left < n; left += width * 2) {
            size_t mid = left + width;
            size_t right = left + width * 2;
            if (mid > n) mid = n;
            if (right > n) right = n;
            size_t i = left, j = mid, k = left;
            while (i < mid && j < right) {
                if (na_key_before(&items[i], &items[j])) tmp[k++] = items[i++];
                else tmp[k++] = items[j++];
            }
            while (i < mid) tmp[k++] = items[i++];
            while (j < right) tmp[k++] = items[j++];
            for (k = left; k < right; ++k) items[k] = tmp[k];
        }
        if (width > n / 2) break;
    }
}

int32_t na_decode_quad_centroids(void *dst_xyz, const void *vertex_data, uint64_t vertex_count,
                                 uint32_t vertex_stride, uint32_t position_offset) {
    if (dst_xyz == NULL || vertex_data == NULL || vertex_stride < position_offset + 12u) return -1;
    const uint64_t quad_count = vertex_count / 4u;
    uint8_t *out = (uint8_t *)dst_xyz;
    const uint8_t *vertices = (const uint8_t *)vertex_data;

    for (uint64_t q = 0; q < quad_count; ++q) {
        const uint64_t first = q * 4u * (uint64_t)vertex_stride + position_offset;
        const uint64_t second = first + 2u * (uint64_t)vertex_stride;
        const float x = (na_load_f32(vertices + first) + na_load_f32(vertices + second)) * 0.5f;
        const float y = (na_load_f32(vertices + first + 4u) + na_load_f32(vertices + second + 4u)) * 0.5f;
        const float z = (na_load_f32(vertices + first + 8u) + na_load_f32(vertices + second + 8u)) * 0.5f;
        na_store_f32(out + q * 12u, x);
        na_store_f32(out + q * 12u + 4u, y);
        na_store_f32(out + q * 12u + 8u, z);
    }
    return 0;
}

int32_t na_sort_quad_indices_distance(void *dst_quad_indices, const void *centroids_xyz, uint64_t quad_count,
                                      float origin_x, float origin_y, float origin_z) {
    if (dst_quad_indices == NULL || centroids_xyz == NULL) return -1;
    if (quad_count > UINT32_MAX) return -2;
    na_quad_key *items = (na_quad_key *)malloc((size_t)quad_count * sizeof(*items));
    na_quad_key *tmp = (na_quad_key *)malloc((size_t)quad_count * sizeof(*tmp));
    if ((quad_count != 0u) && (items == NULL || tmp == NULL)) {
        free(items); free(tmp); return -3;
    }

    const uint8_t *centroids = (const uint8_t *)centroids_xyz;
    for (uint64_t i = 0; i < quad_count; ++i) {
        const float dx = na_load_f32(centroids + i * 12u) - origin_x;
        const float dy = na_load_f32(centroids + i * 12u + 4u) - origin_y;
        const float dz = na_load_f32(centroids + i * 12u + 8u) - origin_z;
        items[i].distance = dx * dx + dy * dy + dz * dz;
        items[i].index = (uint32_t)i;
    }
    na_merge_sort_keys(items, tmp, (size_t)quad_count);
    uint8_t *out = (uint8_t *)dst_quad_indices;
    for (uint64_t i = 0; i < quad_count; ++i) {
        memcpy(out + i * 4u, &items[i].index, 4u);
    }
    free(tmp);
    free(items);
    return 0;
}

int32_t na_sort_quads_distance_write_indices(void *dst_indices, uint32_t index_bytes,
                                             const void *vertex_data, uint64_t vertex_count,
                                             uint32_t vertex_stride, uint32_t position_offset,
                                             float origin_x, float origin_y, float origin_z) {
    if (dst_indices == NULL || vertex_data == NULL || (index_bytes != 2u && index_bytes != 4u)) return -1;
    const uint64_t quad_count = vertex_count / 4u;
    if (quad_count > UINT32_MAX) return -2;
    if (index_bytes == 2u && vertex_count > 65536u) return -4;

    float *centroids = (float *)malloc((size_t)quad_count * 3u * sizeof(float));
    uint32_t *order = (uint32_t *)malloc((size_t)quad_count * sizeof(uint32_t));
    if (quad_count != 0u && (centroids == NULL || order == NULL)) {
        free(centroids); free(order); return -3;
    }
    int32_t rc = na_decode_quad_centroids(centroids, vertex_data, vertex_count, vertex_stride, position_offset);
    if (rc == 0) rc = na_sort_quad_indices_distance(order, centroids, quad_count, origin_x, origin_y, origin_z);
    if (rc != 0) {
        free(order); free(centroids); return rc;
    }

    uint8_t *out = (uint8_t *)dst_indices;
    for (uint64_t i = 0; i < quad_count; ++i) {
        const uint32_t b = order[i] * 4u;
        const uint32_t six[6] = {b, b + 1u, b + 2u, b + 2u, b + 3u, b};
        if (index_bytes == 2u) {
            for (uint32_t j = 0; j < 6u; ++j) {
                const uint16_t v = (uint16_t)six[j];
                memcpy(out, &v, 2u); out += 2u;
            }
        } else {
            for (uint32_t j = 0; j < 6u; ++j) {
                memcpy(out, &six[j], 4u); out += 4u;
            }
        }
    }
    free(order);
    free(centroids);
    return 0;
}
