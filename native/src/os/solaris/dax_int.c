/*
 * Solaris Data Analytics Accelerator integer scan/select adapter.
 *
 * libdax compares fixed-width values as unsigned, big-endian quantities.
 * Java int predicates are signed, so ranges crossing zero are represented as
 * an unsigned OR range: value <= positiveUpper || value >= negativeLower.
 *
 * A dax_context_t belongs to the thread that created it.  World-generation
 * and other bulk workers therefore get one lazy context each via pthread TLS.
 */

#include "native_accelerator_dax.h"

#include <dax.h>
#include <pthread.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#define NA_DAX_OK          0
#define NA_DAX_EINVAL      1
#define NA_DAX_ENOMEM      2
#define NA_DAX_ECONTEXT    3
#define NA_DAX_ECREATEINT  4
#define NA_DAX_ESCAN       5
#define NA_DAX_ESELECT     6


static int na_dax_host_is_big_endian(void) {
#ifdef NA_DAX_TEST_ASSUME_BIG_ENDIAN
    return 1;
#else
    const uint16_t marker = 0x0102U;
    return *((const unsigned char *) &marker) == 0x01U;
#endif
}

static uint32_t na_dax_bswap32(uint32_t value) {
    return ((value & 0x000000ffU) << 24) |
           ((value & 0x0000ff00U) << 8)  |
           ((value & 0x00ff0000U) >> 8)  |
           ((value & 0xff000000U) >> 24);
}

/* libdax fixed-width elements are big endian. Avoid a conversion copy on SPARC BE. */
static int na_dax_prepare_i32_source(const int32_t *src, uint64_t count,
                                     const int32_t **prepared, void **owned) {
    uint32_t *copy;
    size_t bytes;
    uint64_t i;

    if (prepared == NULL || owned == NULL || (count != 0U && src == NULL) ||
        count > (uint64_t) SIZE_MAX / sizeof (int32_t)) {
        return NA_DAX_EINVAL;
    }
    *owned = NULL;
    *prepared = src;
    if (count == 0U || na_dax_host_is_big_endian()) {
        return NA_DAX_OK;
    }

    bytes = (size_t) count * sizeof (int32_t);
    if (posix_memalign(owned, 64U, bytes) != 0 || *owned == NULL) {
        return NA_DAX_ENOMEM;
    }
    copy = (uint32_t *) *owned;
    for (i = 0; i < count; ++i) {
        /* On a little-endian host, storing the swapped word yields BE bytes. */
        copy[i] = na_dax_bswap32((uint32_t) src[i]);
    }
    *prepared = (const int32_t *) copy;
    return NA_DAX_OK;
}

static void na_dax_copy_i32_output(int32_t *dst, const void *src_be, uint64_t count) {
    uint64_t i;
    const unsigned char *bytes = (const unsigned char *) src_be;
    if (na_dax_host_is_big_endian()) {
        memcpy(dst, src_be, (size_t) count * sizeof (int32_t));
        return;
    }
    for (i = 0; i < count; ++i) {
        const unsigned char *p = bytes + i * 4U;
        uint32_t value = ((uint32_t) p[0] << 24) | ((uint32_t) p[1] << 16) |
                         ((uint32_t) p[2] << 8) | (uint32_t) p[3];
        dst[i] = (int32_t) value;
    }
}

static pthread_once_t na_dax_key_once = PTHREAD_ONCE_INIT;
static pthread_key_t na_dax_context_key;
static int na_dax_key_valid = 0;

static void na_dax_destroy_context(void *value) {
    dax_context_t *ctx = (dax_context_t *) value;
    if (ctx != NULL) {
        (void) dax_thread_fini(ctx);
    }
}

static void na_dax_make_context_key(void) {
    if (pthread_key_create(&na_dax_context_key, na_dax_destroy_context) == 0) {
        na_dax_key_valid = 1;
    }
}

static dax_context_t *na_dax_context(void) {
    dax_context_t *ctx;
    dax_status_t status;

    (void) pthread_once(&na_dax_key_once, na_dax_make_context_key);
    if (!na_dax_key_valid) {
        return NULL;
    }

    ctx = (dax_context_t *) pthread_getspecific(na_dax_context_key);
    if (ctx != NULL) {
        return ctx;
    }

    /* API 2.1 supports the widest fixed-width vectors exposed by libdax 2. */
    status = dax_thread_init(2U, 1U, 0U, NULL, &ctx);
    if (status != DAX_SUCCESS || ctx == NULL) {
        return NULL;
    }
    if (pthread_setspecific(na_dax_context_key, ctx) != 0) {
        (void) dax_thread_fini(ctx);
        return NULL;
    }
    return ctx;
}

static int na_dax_aligned_output(uint64_t elements, unsigned bits_per_element,
                                 void **out, size_t *out_bytes) {
    size_t bytes;
    void *ptr = NULL;

    if (out == NULL || out_bytes == NULL || bits_per_element == 0U) {
        return NA_DAX_EINVAL;
    }

    /* Java callers are array/direct-buffer sized, but keep arithmetic bounded. */
    if (elements > ((uint64_t) SIZE_MAX / (uint64_t) bits_per_element) - 1024U) {
        return NA_DAX_EINVAL;
    }

    bytes = (size_t) DAX_OUTPUT_SIZE(elements, bits_per_element);
    if (bytes == 0U) {
        bytes = 64U;
    }
    if (posix_memalign(&ptr, 64U, bytes) != 0 || ptr == NULL) {
        return NA_DAX_ENOMEM;
    }
    memset(ptr, 0, bytes);
    *out = ptr;
    *out_bytes = bytes;
    return NA_DAX_OK;
}

static dax_status_t na_dax_make_u32(dax_context_t *ctx, uint32_t value, dax_int_t *out) {
    unsigned char be[4];
    be[0] = (unsigned char) (value >> 24);
    be[1] = (unsigned char) (value >> 16);
    be[2] = (unsigned char) (value >> 8);
    be[3] = (unsigned char) value;
    return dax_int_create(ctx, be, sizeof (be), out);
}

/*
 * Build an unsigned libdax range equivalent to Java's signed inclusive range.
 * If lower < 0 <= upper, signed ordering wraps at the unsigned sign bit.
 */
static int na_dax_signed_range(dax_context_t *ctx,
                               int32_t lower, int32_t upper,
                               dax_compare_t *op,
                               dax_int_t *dax_lower,
                               dax_int_t *dax_upper) {
    dax_status_t status;
    uint32_t first;
    uint32_t second;

    if (ctx == NULL || op == NULL || dax_lower == NULL || dax_upper == NULL || lower > upper) {
        return NA_DAX_EINVAL;
    }

    if (lower < 0 && upper >= 0) {
        /* unsigned: element <= upper || element >= lower */
        *op = DAX_LE_OR_GE;
        first = (uint32_t) upper;
        second = (uint32_t) lower;
    } else {
        *op = DAX_GE_AND_LE;
        first = (uint32_t) lower;
        second = (uint32_t) upper;
    }

    status = na_dax_make_u32(ctx, first, dax_lower);
    if (status != DAX_SUCCESS) {
        return NA_DAX_ECREATEINT;
    }
    status = na_dax_make_u32(ctx, second, dax_upper);
    return status == DAX_SUCCESS ? NA_DAX_OK : NA_DAX_ECREATEINT;
}

static int na_dax_scan_i32(dax_context_t *ctx,
                           const int32_t *src_data, uint64_t count,
                           int32_t lower, int32_t upper,
                           void **mask_storage, size_t *mask_bytes,
                           dax_vec_t *src, dax_vec_t *mask,
                           uint64_t *matched) {
    dax_int_t dax_lower;
    dax_int_t dax_upper;
    dax_compare_t op;
    dax_result_t result;
    int rc;

    if (ctx == NULL || src_data == NULL || mask_storage == NULL || mask_bytes == NULL ||
        src == NULL || mask == NULL || matched == NULL || lower > upper) {
        return NA_DAX_EINVAL;
    }

    rc = na_dax_aligned_output(count, 1U, mask_storage, mask_bytes);
    if (rc != NA_DAX_OK) {
        return rc;
    }

    memset(src, 0, sizeof (*src));
    src->elements = count;
    src->data = (void *) src_data;
    src->format = DAX_FIXED | DAX_BYTES;
    src->elem_width = 4;
    src->offset = 0;

    memset(mask, 0, sizeof (*mask));
    mask->elements = count;
    mask->data = *mask_storage;
    mask->format = DAX_FIXED | DAX_BITS;
    mask->elem_width = 1;
    mask->offset = 0;

    rc = na_dax_signed_range(ctx, lower, upper, &op, &dax_lower, &dax_upper);
    if (rc != NA_DAX_OK) {
        free(*mask_storage);
        *mask_storage = NULL;
        return rc;
    }

    result = dax_scan_range(ctx, DAX_CACHE_DST, src, mask, op, &dax_lower, &dax_upper);
    if (result.status != DAX_SUCCESS) {
        free(*mask_storage);
        *mask_storage = NULL;
        return NA_DAX_ESCAN;
    }

    *matched = result.count;
    return NA_DAX_OK;
}

int na_dax_i32_count_range(const int32_t *src, uint64_t count,
                           int32_t lower_inclusive, int32_t upper_inclusive,
                           uint64_t *matched) {
    dax_context_t *ctx;
    dax_vec_t src_vec;
    dax_vec_t mask_vec;
    const int32_t *prepared_src = src;
    void *source_storage = NULL;
    void *mask_storage = NULL;
    size_t mask_bytes = 0U;
    int rc;

    if (matched == NULL || lower_inclusive > upper_inclusive || (count != 0U && src == NULL)) {
        return NA_DAX_EINVAL;
    }
    *matched = 0U;
    if (count == 0U) {
        return NA_DAX_OK;
    }

    ctx = na_dax_context();
    if (ctx == NULL) {
        return NA_DAX_ECONTEXT;
    }
    rc = na_dax_prepare_i32_source(src, count, &prepared_src, &source_storage);
    if (rc != NA_DAX_OK) {
        return rc;
    }

    rc = na_dax_scan_i32(ctx, prepared_src, count, lower_inclusive, upper_inclusive,
                         &mask_storage, &mask_bytes, &src_vec, &mask_vec, matched);
    free(mask_storage);
    free(source_storage);
    return rc;
}

int na_dax_i32_select_range(int32_t *dst, uint64_t dst_capacity,
                            const int32_t *src, uint64_t count,
                            int32_t lower_inclusive, int32_t upper_inclusive,
                            uint64_t *selected) {
    dax_context_t *ctx;
    dax_vec_t src_vec;
    dax_vec_t mask_vec;
    dax_vec_t dst_vec;
    dax_result_t result;
    const int32_t *prepared_src = src;
    void *source_storage = NULL;
    void *mask_storage = NULL;
    void *dst_storage = NULL;
    size_t mask_bytes = 0U;
    size_t dst_bytes = 0U;
    uint64_t matched = 0U;
    int rc;

    if (selected == NULL || lower_inclusive > upper_inclusive ||
        (count != 0U && src == NULL) || (dst_capacity != 0U && dst == NULL)) {
        return NA_DAX_EINVAL;
    }
    *selected = 0U;
    if (count == 0U) {
        return NA_DAX_OK;
    }

    ctx = na_dax_context();
    if (ctx == NULL) {
        return NA_DAX_ECONTEXT;
    }
    rc = na_dax_prepare_i32_source(src, count, &prepared_src, &source_storage);
    if (rc != NA_DAX_OK) {
        return rc;
    }

    rc = na_dax_scan_i32(ctx, prepared_src, count, lower_inclusive, upper_inclusive,
                         &mask_storage, &mask_bytes, &src_vec, &mask_vec, &matched);
    if (rc != NA_DAX_OK) {
        free(source_storage);
        return rc;
    }
    if (matched > dst_capacity) {
        free(mask_storage);
        free(source_storage);
        return NA_DAX_EINVAL;
    }
    if (matched == 0U) {
        free(mask_storage);
        free(source_storage);
        return NA_DAX_OK;
    }

    rc = na_dax_aligned_output(matched, 32U, &dst_storage, &dst_bytes);
    if (rc != NA_DAX_OK) {
        free(mask_storage);
        free(source_storage);
        return rc;
    }

    memset(&dst_vec, 0, sizeof (dst_vec));
    dst_vec.elements = matched;
    dst_vec.data = dst_storage;
    dst_vec.format = DAX_FIXED | DAX_BYTES;
    dst_vec.elem_width = 4;
    dst_vec.offset = 0;

    result = dax_select(ctx, DAX_CACHE_DST, &src_vec, &dst_vec, &mask_vec);
    if (result.status != DAX_SUCCESS || result.count != matched) {
        free(dst_storage);
        free(mask_storage);
        free(source_storage);
        return NA_DAX_ESELECT;
    }

    na_dax_copy_i32_output(dst, dst_storage, matched);
    *selected = matched;

    free(dst_storage);
    free(mask_storage);
    free(source_storage);
    return NA_DAX_OK;
}
