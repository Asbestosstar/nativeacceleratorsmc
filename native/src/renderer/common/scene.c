#include "native_accelerator_renderer.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

typedef struct nar_section_entry {
    uint64_t key;
    float min_x, min_y, min_z;
    float max_x, max_y, max_z;
    uint32_t first_index;
    uint32_t index_count;
    int32_t vertex_offset;
    uint32_t first_instance;
    uint32_t material_mask;
    uint8_t state; /* 0 empty, 1 occupied, 2 tombstone */
} nar_section_entry;

struct nar_scene {
    nar_section_entry *entries;
    uint32_t capacity;
    uint32_t count;
    uint32_t tombstones;
};

static uint64_t mix64(uint64_t x) {
    x ^= x >> 30;
    x *= UINT64_C(0xbf58476d1ce4e5b9);
    x ^= x >> 27;
    x *= UINT64_C(0x94d049bb133111eb);
    x ^= x >> 31;
    return x;
}

static uint32_t round_capacity(uint32_t requested) {
    uint32_t capacity = 16;
    while (capacity < requested && capacity <= UINT32_MAX / 2u) capacity <<= 1u;
    return capacity;
}

static int scene_insert_raw(nar_scene *scene, const nar_section_entry *entry) {
    uint32_t mask = scene->capacity - 1u;
    uint32_t slot = (uint32_t)mix64(entry->key) & mask;
    while (scene->entries[slot].state == 1) slot = (slot + 1u) & mask;
    scene->entries[slot] = *entry;
    scene->entries[slot].state = 1;
    scene->count++;
    return 0;
}

static int scene_rehash(nar_scene *scene, uint32_t new_capacity) {
    nar_section_entry *old_entries = scene->entries;
    uint32_t old_capacity = scene->capacity;
    nar_section_entry *new_entries = (nar_section_entry *)calloc(new_capacity, sizeof(*new_entries));
    if (new_entries == NULL) return -1;
    scene->entries = new_entries;
    scene->capacity = new_capacity;
    scene->count = 0;
    scene->tombstones = 0;
    for (uint32_t i = 0; i < old_capacity; ++i) {
        if (old_entries[i].state == 1) scene_insert_raw(scene, &old_entries[i]);
    }
    free(old_entries);
    return 0;
}

static nar_section_entry *scene_find_slot(nar_scene *scene, uint64_t key, int for_insert) {
    uint32_t mask = scene->capacity - 1u;
    uint32_t slot = (uint32_t)mix64(key) & mask;
    nar_section_entry *first_tombstone = NULL;
    for (;;) {
        nar_section_entry *entry = &scene->entries[slot];
        if (entry->state == 0) return for_insert && first_tombstone != NULL ? first_tombstone : entry;
        if (entry->state == 1 && entry->key == key) return entry;
        if (for_insert && entry->state == 2 && first_tombstone == NULL) first_tombstone = entry;
        slot = (slot + 1u) & mask;
    }
}

nar_scene *nar_scene_create(uint32_t initial_capacity) {
    nar_scene *scene = (nar_scene *)calloc(1, sizeof(*scene));
    if (scene == NULL) return NULL;
    scene->capacity = round_capacity(initial_capacity == 0 ? 1024u : initial_capacity * 2u);
    scene->entries = (nar_section_entry *)calloc(scene->capacity, sizeof(*scene->entries));
    if (scene->entries == NULL) {
        free(scene);
        return NULL;
    }
    return scene;
}

void nar_scene_destroy(nar_scene *scene) {
    if (scene == NULL) return;
    free(scene->entries);
    free(scene);
}

uint32_t nar_scene_count(const nar_scene *scene) {
    return scene == NULL ? 0u : scene->count;
}

int32_t nar_scene_upsert(nar_scene *scene,
                         uint64_t section_key,
                         float min_x, float min_y, float min_z,
                         float max_x, float max_y, float max_z,
                         uint32_t first_index, uint32_t index_count,
                         int32_t vertex_offset, uint32_t first_instance,
                         uint32_t material_mask) {
    if (scene == NULL || min_x > max_x || min_y > max_y || min_z > max_z) return -1;
    if ((scene->count + scene->tombstones + 1u) * 10u >= scene->capacity * 7u) {
        if (scene_rehash(scene, scene->capacity << 1u) != 0) return -2;
    }
    nar_section_entry *entry = scene_find_slot(scene, section_key, 1);
    int was_occupied = entry->state == 1;
    if (entry->state == 2) scene->tombstones--;
    entry->key = section_key;
    entry->min_x = min_x; entry->min_y = min_y; entry->min_z = min_z;
    entry->max_x = max_x; entry->max_y = max_y; entry->max_z = max_z;
    entry->first_index = first_index;
    entry->index_count = index_count;
    entry->vertex_offset = vertex_offset;
    entry->first_instance = first_instance;
    entry->material_mask = material_mask;
    entry->state = 1;
    if (!was_occupied) scene->count++;
    return 0;
}

int32_t nar_scene_remove(nar_scene *scene, uint64_t section_key) {
    if (scene == NULL) return -1;
    nar_section_entry *entry = scene_find_slot(scene, section_key, 0);
    if (entry->state != 1) return 1;
    entry->state = 2;
    scene->count--;
    scene->tombstones++;
    if (scene->tombstones > scene->count && scene->capacity > 16u) (void)scene_rehash(scene, scene->capacity);
    return 0;
}

static int aabb_inside_frustum(const nar_section_entry *e, const float *planes) {
    if (planes == NULL) return 1;
    for (int p = 0; p < 6; ++p) {
        const float *plane = planes + p * 4;
        float x = plane[0] >= 0.0f ? e->max_x : e->min_x;
        float y = plane[1] >= 0.0f ? e->max_y : e->min_y;
        float z = plane[2] >= 0.0f ? e->max_z : e->min_z;
        if (plane[0] * x + plane[1] * y + plane[2] * z + plane[3] < 0.0f) return 0;
    }
    return 1;
}

static float axis_distance(float point, float min_value, float max_value) {
    if (point < min_value) return min_value - point;
    if (point > max_value) return point - max_value;
    return 0.0f;
}

static int aabb_inside_distance(const nar_section_entry *e, float x, float y, float z, float max_distance_sq) {
    if (max_distance_sq <= 0.0f) return 1;
    float dx = axis_distance(x, e->min_x, e->max_x);
    float dy = axis_distance(y, e->min_y, e->max_y);
    float dz = axis_distance(z, e->min_z, e->max_z);
    return dx * dx + dy * dy + dz * dz <= max_distance_sq;
}

int32_t nar_scene_build_indirect(const nar_scene *scene,
                                 const float *frustum_planes,
                                 float camera_x, float camera_y, float camera_z,
                                 float max_distance_sq,
                                 uint32_t required_material_mask,
                                 nar_draw_indexed_indirect_command *out_commands,
                                 uint64_t *out_section_keys,
                                 uint32_t output_capacity,
                                 uint32_t *out_count) {
    if (scene == NULL || out_count == NULL) return -1;
    if (output_capacity > 0 && out_commands == NULL) return -2;
    uint32_t visible = 0;
    for (uint32_t i = 0; i < scene->capacity; ++i) {
        const nar_section_entry *entry = &scene->entries[i];
        if (entry->state != 1 || entry->index_count == 0) continue;
        if (required_material_mask != 0 && (entry->material_mask & required_material_mask) == 0) continue;
        if (!aabb_inside_frustum(entry, frustum_planes)) continue;
        if (!aabb_inside_distance(entry, camera_x, camera_y, camera_z, max_distance_sq)) continue;
        if (visible < output_capacity) {
            nar_draw_indexed_indirect_command *command = &out_commands[visible];
            command->index_count = entry->index_count;
            command->instance_count = 1;
            command->first_index = entry->first_index;
            command->vertex_offset = entry->vertex_offset;
            command->first_instance = entry->first_instance;
            if (out_section_keys != NULL) out_section_keys[visible] = entry->key;
        }
        visible++;
    }
    *out_count = visible;
    return visible > output_capacity ? 1 : 0;
}

typedef struct nar_gpu_draw_meta {
    uint32_t index_count;
    uint32_t first_index;
    int32_t vertex_offset;
    uint32_t first_instance;
    uint32_t material_mask;
} nar_gpu_draw_meta;

int32_t nar_scene_export_gpu(const nar_scene *scene,
                             float *out_bounds_min_vec4,
                             float *out_bounds_max_vec4,
                             void *out_draw_meta_20b,
                             uint64_t *out_section_keys,
                             uint32_t output_capacity,
                             uint32_t *out_count) {
    if (scene == NULL || out_count == NULL) return -1;
    if (output_capacity > 0 && (out_bounds_min_vec4 == NULL || out_bounds_max_vec4 == NULL || out_draw_meta_20b == NULL)) return -2;
    nar_gpu_draw_meta *meta = (nar_gpu_draw_meta *)out_draw_meta_20b;
    uint32_t written = 0;
    for (uint32_t i = 0; i < scene->capacity; ++i) {
        const nar_section_entry *entry = &scene->entries[i];
        if (entry->state != 1) continue;
        if (written < output_capacity) {
            float *bmin = out_bounds_min_vec4 + (size_t)written * 4u;
            float *bmax = out_bounds_max_vec4 + (size_t)written * 4u;
            bmin[0] = entry->min_x; bmin[1] = entry->min_y; bmin[2] = entry->min_z; bmin[3] = 0.0f;
            bmax[0] = entry->max_x; bmax[1] = entry->max_y; bmax[2] = entry->max_z; bmax[3] = 0.0f;
            meta[written].index_count = entry->index_count;
            meta[written].first_index = entry->first_index;
            meta[written].vertex_offset = entry->vertex_offset;
            meta[written].first_instance = entry->first_instance;
            meta[written].material_mask = entry->material_mask;
            if (out_section_keys != NULL) out_section_keys[written] = entry->key;
        }
        written++;
    }
    *out_count = written;
    return written > output_capacity ? 1 : 0;
}
