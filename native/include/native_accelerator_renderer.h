#ifndef NATIVE_ACCELERATOR_RENDERER_H
#define NATIVE_ACCELERATOR_RENDERER_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#  define NAR_EXPORT __declspec(dllexport)
#else
#  define NAR_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define NAR_ABI_VERSION 1u

#define NAR_CAP_VULKAN_LOADER        (UINT64_C(1) << 0)
#define NAR_CAP_SCENE_DATABASE       (UINT64_C(1) << 1)
#define NAR_CAP_ARENA_ALLOCATOR      (UINT64_C(1) << 2)
#define NAR_CAP_CPU_FRUSTUM_CULL     (UINT64_C(1) << 3)
#define NAR_CAP_INDIRECT_COMMANDS    (UINT64_C(1) << 4)
#define NAR_CAP_VOXEL_FACE_MASKS     (UINT64_C(1) << 5)
#define NAR_CAP_NATIVE_WORKERS       (UINT64_C(1) << 6)
#define NAR_CAP_PIPELINE_CACHE_IO    (UINT64_C(1) << 7)
#define NAR_CAP_VULKAN_DEVICE        (UINT64_C(1) << 8)

#define NAR_FACE_NEG_X 0x01u
#define NAR_FACE_POS_X 0x02u
#define NAR_FACE_NEG_Y 0x04u
#define NAR_FACE_POS_Y 0x08u
#define NAR_FACE_NEG_Z 0x10u
#define NAR_FACE_POS_Z 0x20u

typedef struct nar_context nar_context;
typedef struct nar_scene nar_scene;
typedef struct nar_arena nar_arena;

typedef struct nar_draw_indexed_indirect_command {
    uint32_t index_count;
    uint32_t instance_count;
    uint32_t first_index;
    int32_t vertex_offset;
    uint32_t first_instance;
} nar_draw_indexed_indirect_command;

typedef struct nar_arena_stats {
    uint64_t capacity;
    uint64_t used;
    uint64_t free_bytes;
    uint64_t largest_free_block;
    uint32_t free_block_count;
    uint32_t allocation_count;
} nar_arena_stats;

NAR_EXPORT uint32_t nar_abi_version(void);
NAR_EXPORT uint64_t nar_capabilities(void);
NAR_EXPORT uint64_t nar_backend_name(char *dst, uint64_t capacity);

/* Vulkan loader discovery is runtime based. No OS is excluded by name. */
NAR_EXPORT int32_t nar_vulkan_loader_available(void);
NAR_EXPORT uint32_t nar_vulkan_loader_api_version(void);
NAR_EXPORT uint32_t nar_vulkan_physical_device_count(void);
NAR_EXPORT uint64_t nar_vulkan_loader_name(char *dst, uint64_t capacity);

/* Long-lived native renderer context. Worker count 0 selects an implementation default. */
NAR_EXPORT nar_context *nar_context_create(uint32_t worker_count);
NAR_EXPORT void nar_context_destroy(nar_context *context);
NAR_EXPORT uint32_t nar_context_worker_count(const nar_context *context);

/* Persistent suballocation for large GPU buffer arenas. */
NAR_EXPORT nar_arena *nar_arena_create(uint64_t capacity, uint64_t default_alignment);
NAR_EXPORT void nar_arena_destroy(nar_arena *arena);
NAR_EXPORT int32_t nar_arena_alloc(nar_arena *arena, uint64_t size, uint64_t alignment, uint64_t *out_offset);
NAR_EXPORT int32_t nar_arena_free(nar_arena *arena, uint64_t offset, uint64_t size);
NAR_EXPORT int32_t nar_arena_get_stats(const nar_arena *arena, nar_arena_stats *out_stats);

/* Section scene database. Bounds are world-space AABBs. */
NAR_EXPORT nar_scene *nar_scene_create(uint32_t initial_capacity);
NAR_EXPORT void nar_scene_destroy(nar_scene *scene);
NAR_EXPORT uint32_t nar_scene_count(const nar_scene *scene);
NAR_EXPORT int32_t nar_scene_upsert(nar_scene *scene,
                                    uint64_t section_key,
                                    float min_x, float min_y, float min_z,
                                    float max_x, float max_y, float max_z,
                                    uint32_t first_index, uint32_t index_count,
                                    int32_t vertex_offset, uint32_t first_instance,
                                    uint32_t material_mask);
NAR_EXPORT int32_t nar_scene_remove(nar_scene *scene, uint64_t section_key);

/*
 * CPU fallback and validation path for GPU-driven visibility. frustum_planes is
 * 6 planes of (a,b,c,d). max_distance_sq <= 0 disables distance culling.
 * required_material_mask == 0 accepts every material.
 */
NAR_EXPORT int32_t nar_scene_build_indirect(const nar_scene *scene,
                                            const float *frustum_planes,
                                            float camera_x, float camera_y, float camera_z,
                                            float max_distance_sq,
                                            uint32_t required_material_mask,
                                            nar_draw_indexed_indirect_command *out_commands,
                                            uint64_t *out_section_keys,
                                            uint32_t output_capacity,
                                            uint32_t *out_count);

/* Export the persistent scene into GPU-friendly SoA buffers for compute culling. */
NAR_EXPORT int32_t nar_scene_export_gpu(const nar_scene *scene,
                                        float *out_bounds_min_vec4,
                                        float *out_bounds_max_vec4,
                                        void *out_draw_meta_20b,
                                        uint64_t *out_section_keys,
                                        uint32_t output_capacity,
                                        uint32_t *out_count);

/*
 * Build one 6-bit visible-face mask per voxel in a 16^3 section.
 * Occupancy uses Minecraft section ordering x | (z << 4) | (y << 8).
 * neighbor_planes may be NULL (outside treated empty) or 6*256 bytes ordered
 * NEG_X, POS_X, NEG_Y, POS_Y, NEG_Z, POS_Z.
 */
NAR_EXPORT int32_t nar_voxel_face_masks(uint8_t *out_masks,
                                        const uint8_t *occupancy_4096,
                                        const uint8_t *neighbor_planes_1536);
NAR_EXPORT int32_t nar_voxel_face_masks_batch(nar_context *context,
                                              uint8_t *out_masks,
                                              const uint8_t *occupancy,
                                              const uint8_t *neighbor_planes,
                                              uint32_t section_count);

/* Driver pipeline-cache blob persistence. Return 0 on success. */
NAR_EXPORT uint64_t nar_pipeline_cache_key(const void *data, uint64_t size, uint64_t seed);
NAR_EXPORT int32_t nar_pipeline_cache_write(const char *path, const void *data, uint64_t size);
NAR_EXPORT int64_t nar_pipeline_cache_read(const char *path, void *dst, uint64_t capacity);

/* Internal job system used by the renderer implementation. */
typedef void (*nar_parallel_fn)(void *userdata, uint32_t index);
int32_t nar_parallel_for(nar_context *context, uint32_t count, nar_parallel_fn function, void *userdata);

#ifdef __cplusplus
}
#endif

#endif
