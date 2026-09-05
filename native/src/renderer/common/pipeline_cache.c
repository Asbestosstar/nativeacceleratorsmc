#include "native_accelerator_renderer.h"

#include <stdio.h>
#include <stdlib.h>

uint64_t nar_pipeline_cache_key(const void *data, uint64_t size, uint64_t seed) {
    const uint8_t *bytes = (const uint8_t *)data;
    uint64_t hash = UINT64_C(1469598103934665603) ^ seed;
    if (bytes == NULL && size != 0) return 0;
    for (uint64_t i = 0; i < size; ++i) {
        hash ^= bytes[i];
        hash *= UINT64_C(1099511628211);
    }
    return hash;
}

int32_t nar_pipeline_cache_write(const char *path, const void *data, uint64_t size) {
    if (path == NULL || (data == NULL && size != 0) || size > (uint64_t)SIZE_MAX) return -1;
    FILE *file = fopen(path, "wb");
    if (file == NULL) return -2;
    size_t written = size == 0 ? 0 : fwrite(data, 1, (size_t)size, file);
    int close_rc = fclose(file);
    return (written == (size_t)size && close_rc == 0) ? 0 : -3;
}

int64_t nar_pipeline_cache_read(const char *path, void *dst, uint64_t capacity) {
    if (path == NULL || (dst == NULL && capacity != 0) || capacity > (uint64_t)SIZE_MAX) return -1;
    FILE *file = fopen(path, "rb");
    if (file == NULL) return -2;
    if (fseek(file, 0, SEEK_END) != 0) { fclose(file); return -3; }
    long file_size = ftell(file);
    if (file_size < 0) { fclose(file); return -3; }
    if (fseek(file, 0, SEEK_SET) != 0) { fclose(file); return -3; }
    if ((uint64_t)file_size > capacity) { fclose(file); return (int64_t)file_size; }
    size_t read = file_size == 0 ? 0 : fread(dst, 1, (size_t)file_size, file);
    fclose(file);
    return read == (size_t)file_size ? (int64_t)file_size : -4;
}
