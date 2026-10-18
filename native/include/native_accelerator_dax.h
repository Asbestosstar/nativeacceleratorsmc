#ifndef NATIVE_ACCELERATOR_DAX_H
#define NATIVE_ACCELERATOR_DAX_H

#include <stdint.h>

#if defined(_WIN32)
#  define NA_DAX_EXPORT __declspec(dllexport)
#elif defined(__GNUC__) || defined(__clang__)
#  define NA_DAX_EXPORT __attribute__((visibility("default")))
#else
#  define NA_DAX_EXPORT
#endif

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Optional ABI-v3 extension.  These symbols are compiled only when Solaris
 * libdax development headers/library are available.  Their absence is a
 * supported state; Java discovers them with optional symbol lookup.
 */
NA_DAX_EXPORT int na_dax_i32_count_range(const int32_t *src, uint64_t count,
                           int32_t lower_inclusive, int32_t upper_inclusive,
                           uint64_t *matched);

NA_DAX_EXPORT int na_dax_i32_select_range(int32_t *dst, uint64_t dst_capacity,
                            const int32_t *src, uint64_t count,
                            int32_t lower_inclusive, int32_t upper_inclusive,
                            uint64_t *selected);

#ifdef __cplusplus
}
#endif

#endif
