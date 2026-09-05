#include "native_accelerator_renderer.h"

#include <stdlib.h>
#include <string.h>

typedef struct nar_free_block {
    uint64_t offset;
    uint64_t size;
    struct nar_free_block *next;
} nar_free_block;

struct nar_arena {
    uint64_t capacity;
    uint64_t default_alignment;
    uint64_t used;
    uint32_t allocation_count;
    nar_free_block *free_list;
};

static uint64_t align_up(uint64_t value, uint64_t alignment) {
    if (alignment <= 1) return value;
    uint64_t remainder = value % alignment;
    if (remainder == 0) return value;
    uint64_t add = alignment - remainder;
    if (UINT64_MAX - value < add) return UINT64_MAX;
    return value + add;
}

static nar_free_block *new_block(uint64_t offset, uint64_t size) {
    nar_free_block *block = (nar_free_block *)malloc(sizeof(*block));
    if (block == NULL) return NULL;
    block->offset = offset;
    block->size = size;
    block->next = NULL;
    return block;
}

nar_arena *nar_arena_create(uint64_t capacity, uint64_t default_alignment) {
    if (capacity == 0) return NULL;
    nar_arena *arena = (nar_arena *)calloc(1, sizeof(*arena));
    if (arena == NULL) return NULL;
    arena->capacity = capacity;
    arena->default_alignment = default_alignment == 0 ? 1 : default_alignment;
    arena->free_list = new_block(0, capacity);
    if (arena->free_list == NULL) {
        free(arena);
        return NULL;
    }
    return arena;
}

void nar_arena_destroy(nar_arena *arena) {
    if (arena == NULL) return;
    nar_free_block *block = arena->free_list;
    while (block != NULL) {
        nar_free_block *next = block->next;
        free(block);
        block = next;
    }
    free(arena);
}

int32_t nar_arena_alloc(nar_arena *arena, uint64_t size, uint64_t alignment, uint64_t *out_offset) {
    if (arena == NULL || out_offset == NULL || size == 0) return -1;
    if (alignment == 0) alignment = arena->default_alignment;

    nar_free_block *prev = NULL;
    nar_free_block *block = arena->free_list;
    while (block != NULL) {
        uint64_t aligned = align_up(block->offset, alignment);
        if (aligned == UINT64_MAX || aligned < block->offset) return -2;
        uint64_t prefix = aligned - block->offset;
        if (prefix <= block->size && size <= block->size - prefix) {
            uint64_t suffix = block->size - prefix - size;
            if (prefix != 0 && suffix != 0) {
                nar_free_block *tail = new_block(aligned + size, suffix);
                if (tail == NULL) return -3;
                tail->next = block->next;
                block->size = prefix;
                block->next = tail;
            } else if (prefix != 0) {
                block->size = prefix;
            } else if (suffix != 0) {
                block->offset = aligned + size;
                block->size = suffix;
            } else {
                if (prev != NULL) prev->next = block->next;
                else arena->free_list = block->next;
                free(block);
            }
            arena->used += size;
            arena->allocation_count++;
            *out_offset = aligned;
            return 0;
        }
        prev = block;
        block = block->next;
    }
    return 1;
}

int32_t nar_arena_free(nar_arena *arena, uint64_t offset, uint64_t size) {
    if (arena == NULL || size == 0 || offset > arena->capacity || size > arena->capacity - offset) return -1;
    if (arena->used < size || arena->allocation_count == 0) return -4;
    nar_free_block *insert = new_block(offset, size);
    if (insert == NULL) return -2;

    nar_free_block *prev = NULL;
    nar_free_block *cur = arena->free_list;
    while (cur != NULL && cur->offset < offset) {
        prev = cur;
        cur = cur->next;
    }

    if ((prev != NULL && prev->offset + prev->size > offset) ||
        (cur != NULL && offset + size > cur->offset)) {
        free(insert);
        return -3;
    }

    insert->next = cur;
    if (prev != NULL) prev->next = insert;
    else arena->free_list = insert;

    if (insert->next != NULL && insert->offset + insert->size == insert->next->offset) {
        nar_free_block *next = insert->next;
        insert->size += next->size;
        insert->next = next->next;
        free(next);
    }
    if (prev != NULL && prev->offset + prev->size == insert->offset) {
        prev->size += insert->size;
        prev->next = insert->next;
        free(insert);
        insert = prev;
    }

    arena->used -= size;
    arena->allocation_count--;
    return 0;
}

int32_t nar_arena_get_stats(const nar_arena *arena, nar_arena_stats *out_stats) {
    if (arena == NULL || out_stats == NULL) return -1;
    memset(out_stats, 0, sizeof(*out_stats));
    out_stats->capacity = arena->capacity;
    out_stats->used = arena->used;
    out_stats->free_bytes = arena->capacity - arena->used;
    out_stats->allocation_count = arena->allocation_count;
    for (const nar_free_block *block = arena->free_list; block != NULL; block = block->next) {
        out_stats->free_block_count++;
        if (block->size > out_stats->largest_free_block) out_stats->largest_free_block = block->size;
    }
    return 0;
}
