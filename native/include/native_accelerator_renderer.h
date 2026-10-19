#ifndef NATIVE_ACCELERATOR_RENDERER_H
#define NATIVE_ACCELERATOR_RENDERER_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#  define NAR_EXPORT __declspec(dllexport)
#elif defined(__GNUC__) || defined(__clang__)
#  define NAR_EXPORT __attribute__((visibility("default")))
#else
#  define NAR_EXPORT
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define NAR_ABI_VERSION 2u

/* Optional adaptive Vulkan capability/session ABI. Existing ABI-2 compute symbols remain valid. */
#define NAR_VULKAN_ABI_VERSION 1u
#define NAR_VULKAN_MODE_AUTO      0u
#define NAR_VULKAN_MODE_OFFSCREEN 1u
#define NAR_VULKAN_MODE_PRESENT   2u
#define NAR_VULKAN_TIER_UNAVAILABLE 0u
#define NAR_VULKAN_TIER_BASIC       1u
#define NAR_VULKAN_TIER_PRESENT     2u
#define NAR_VULKAN_TIER_ENHANCED    3u
#define NAR_VULKAN_FEATURE_CLASSIC_RENDER_PASS (UINT64_C(1) << 0)
#define NAR_VULKAN_FEATURE_CPU_CULL             (UINT64_C(1) << 1)
#define NAR_VULKAN_FEATURE_SWAPCHAIN           (UINT64_C(1) << 2)
#define NAR_VULKAN_FEATURE_DYNAMIC_RENDERING   (UINT64_C(1) << 3)
#define NAR_VULKAN_FEATURE_SYNCHRONIZATION2    (UINT64_C(1) << 4)
#define NAR_VULKAN_FEATURE_PUSH_DESCRIPTOR     (UINT64_C(1) << 5)
#define NAR_VULKAN_FEATURE_VERTEX_DIVISOR      (UINT64_C(1) << 6)
#define NAR_VULKAN_FEATURE_GPU_CULL            (UINT64_C(1) << 7)

typedef struct nar_vulkan_capability_report_data {
    uint32_t struct_size;
    uint32_t abi_version;
    uint32_t api_version;
    uint32_t tier;
    uint64_t features;
    uint32_t physical_device_count;
    uint32_t graphics_queue_available;
    uint32_t present_queue_available;
    uint32_t reserved;
    char device_name[256];
    char diagnostic[512];
} nar_vulkan_capability_report;

typedef struct nar_vulkan_session nar_vulkan_session;

/* Optional WSI/swapchain ABI. This is deliberately separate from the headless
 * session ABI so older companion libraries remain binary-compatible. Handles
 * and native window values are opaque; no Xlib, SDL, Vulkan SDK, or loader
 * headers are required by consumers. */
#define NAR_VULKAN_WSI_ABI_VERSION 1u
#define NAR_VULKAN_WSI_PLATFORM_XLIB   1u
#define NAR_VULKAN_WSI_PLATFORM_XCB    2u
#define NAR_VULKAN_WSI_PLATFORM_WAYLAND 3u
#define NAR_VULKAN_WSI_PLATFORM_WIN32  4u
#define NAR_VULKAN_WSI_PLATFORM_COCOA  5u
#define NAR_VULKAN_WSI_PLATFORM_SDL   6u
#define NAR_VULKAN_WSI_FLAG_RESIZABLE  (UINT32_C(1) << 0)
#define NAR_VULKAN_WSI_FLAG_HEADLESS  (UINT32_C(1) << 1)

typedef struct nar_vulkan_wsi_window {
    uint32_t struct_size;
    uint32_t abi_version;
    uint32_t platform;
    uint32_t flags;
    /* Opaque pointer value represented as a fixed-width integer for FFM/ABI stability. */
    uint64_t native_display;
    uint64_t native_window;
    uint32_t width;
    uint32_t height;
    uint32_t reserved;
} nar_vulkan_wsi_window;

#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
_Static_assert(sizeof(nar_vulkan_wsi_window) == 48, "nar_vulkan_wsi_window ABI size");
_Static_assert(offsetof(nar_vulkan_wsi_window, native_display) == 16, "nar_vulkan_wsi_window native_display offset");
_Static_assert(offsetof(nar_vulkan_wsi_window, native_window) == 24, "nar_vulkan_wsi_window native_window offset");
#endif

typedef struct nar_vulkan_wsi_report {
    uint32_t struct_size;
    uint32_t abi_version;
    uint32_t platform;
    uint32_t supported;
    uint32_t graphics_queue_family;
    uint32_t present_queue_family;
    uint32_t format;
    uint32_t color_space;
    uint32_t present_mode;
    uint32_t image_count;
    uint32_t width;
    uint32_t height;
    uint32_t status;
    char diagnostic[384];
} nar_vulkan_wsi_report;

#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
_Static_assert(sizeof(nar_vulkan_wsi_report) == 436, "nar_vulkan_wsi_report ABI size");
_Static_assert(offsetof(nar_vulkan_wsi_report, diagnostic) == 52, "nar_vulkan_wsi_report diagnostic offset");
#endif

typedef struct nar_vulkan_swapchain nar_vulkan_swapchain;

#define NAR_VULKAN_WSI_STATUS_OK             0u
#define NAR_VULKAN_WSI_STATUS_UNAVAILABLE    1u
#define NAR_VULKAN_WSI_STATUS_BLOCKED        2u
#define NAR_VULKAN_WSI_STATUS_OUT_OF_DATE    3u
#define NAR_VULKAN_WSI_STATUS_SUBOPTIMAL     4u
#define NAR_VULKAN_WSI_STATUS_SURFACE_LOST   5u
#define NAR_VULKAN_WSI_STATUS_INVALID        6u
#define NAR_VULKAN_WSI_STATUS_ERROR         7u
#define NAR_VULKAN_WSI_STATUS_NOT_READY      8u
#define NAR_VULKAN_WSI_STATUS_TIMEOUT        9u
#define NAR_VULKAN_WSI_STATUS_PAUSED        10u
#define NAR_VULKAN_WSI_STATUS_DEVICE_LOST   11u
#define NAR_VULKAN_WSI_STATUS_UNSUPPORTED   12u

/* Create a session with a real platform surface and VK_KHR_swapchain enabled.
 * The ordinary nar_vulkan_session_create() remains headless/offscreen. */
NAR_EXPORT nar_vulkan_session *nar_vulkan_session_create_present(
        const nar_vulkan_wsi_window *window, uint32_t width, uint32_t height);
NAR_EXPORT int32_t nar_vulkan_wsi_supported(uint32_t platform, nar_vulkan_wsi_report *out_report);
NAR_EXPORT int32_t nar_vulkan_wsi_report_session(
        const nar_vulkan_session *session, nar_vulkan_wsi_report *out_report);
NAR_EXPORT nar_vulkan_swapchain *nar_vulkan_swapchain_create(
        nar_vulkan_session *session, uint32_t width, uint32_t height);
NAR_EXPORT void nar_vulkan_swapchain_destroy(
        nar_vulkan_session *session, nar_vulkan_swapchain *swapchain);
NAR_EXPORT int32_t nar_vulkan_swapchain_recreate(
        nar_vulkan_session *session, nar_vulkan_swapchain *swapchain,
        uint32_t width, uint32_t height);
NAR_EXPORT int32_t nar_vulkan_swapchain_acquire(
        nar_vulkan_session *session, nar_vulkan_swapchain *swapchain,
        uint32_t *out_image_index);
NAR_EXPORT int32_t nar_vulkan_swapchain_present(
        nar_vulkan_session *session, nar_vulkan_swapchain *swapchain,
        uint32_t image_index);
NAR_EXPORT uint64_t nar_vulkan_wsi_last_error(
        const nar_vulkan_session *session, char *dst, uint64_t capacity);

#define NAR_CAP_VULKAN_LOADER        (UINT64_C(1) << 0)
#define NAR_CAP_SCENE_DATABASE       (UINT64_C(1) << 1)
#define NAR_CAP_ARENA_ALLOCATOR      (UINT64_C(1) << 2)
#define NAR_CAP_CPU_FRUSTUM_CULL     (UINT64_C(1) << 3)
#define NAR_CAP_INDIRECT_COMMANDS    (UINT64_C(1) << 4)
#define NAR_CAP_VOXEL_FACE_MASKS     (UINT64_C(1) << 5)
#define NAR_CAP_NATIVE_WORKERS       (UINT64_C(1) << 6)
#define NAR_CAP_PIPELINE_CACHE_IO    (UINT64_C(1) << 7)
#define NAR_CAP_VULKAN_DEVICE        (UINT64_C(1) << 8)
#define NAR_CAP_CLIENT_RENDERER      (UINT64_C(1) << 9)
#define NAR_CAP_SERVER_ONLY_HOST     (UINT64_C(1) << 10)

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
/* Internal companion entry point used by the session implementation. The returned address is
 * owned by the dynamically loaded Vulkan loader and remains valid for the process lifetime. */
NAR_EXPORT void *nar_vulkan_get_proc_address(const char *name);
/* Capability discovery is advisory until a session is successfully created. */
NAR_EXPORT int32_t nar_vulkan_query_capabilities(uint32_t mode, nar_vulkan_capability_report *out_report);
NAR_EXPORT nar_vulkan_session *nar_vulkan_session_create(uint32_t mode, uint32_t width, uint32_t height);
NAR_EXPORT void nar_vulkan_session_destroy(nar_vulkan_session *session);
NAR_EXPORT uint32_t nar_vulkan_session_tier(const nar_vulkan_session *session);
NAR_EXPORT uint64_t nar_vulkan_session_features(const nar_vulkan_session *session);
NAR_EXPORT uint64_t nar_vulkan_session_last_error(const nar_vulkan_session *session, char *dst, uint64_t capacity);

/*
 * Minimal offscreen RGBA8 rendering ABI (BASIC tier).
 *
 * An offscreen image is a self-contained package: a device-local VkImage (color
 * attachment), a host-visible readback buffer, a VkImageView, and a single-subpass
 * VkRenderPass.  The caller does not interact with Vulkan objects directly; all
 * Vulkan handles stay inside the opaque struct.
 *
 * Typical usage:
 *
 *   nar_vulkan_session *s = nar_vulkan_session_create(NAR_VULKAN_MODE_OFFSCREEN,w,h);
 *   nar_vulkan_offscreen_image *img = nar_vulkan_offscreen_image_create(s, w, h);
 *   // ... record draw calls between begin and end:
 *   nar_vulkan_offscreen_render_begin(s, img, &cb_out);
 *   // <record into cb_out>
 *   nar_vulkan_offscreen_render_end(s, img);
 *   // retrieve pixels:
 *   nar_vulkan_offscreen_readback(s, img, pixel_buf, w * h * 4);
 *   nar_vulkan_offscreen_image_destroy(s, img);
 *   nar_vulkan_session_destroy(s);
 *
 * cb_out is a void* that holds the VkCommandBuffer; the caller may cast it to
 * record additional draw commands between begin and end.  It becomes invalid
 * after nar_vulkan_offscreen_render_end returns.
 *
 * pixel_buf receives tightly packed BGRA8_UNORM pixels (Vulkan canonical
 * swapchain order); capacity must be at least width*height*4 bytes.
 *
 * All functions return 0 on success, a negative value on hard error, or
 * NAR_OFFSCREEN_RESULT_NO_VULKAN (1) when no Vulkan loader/device is present.
 */

#define NAR_OFFSCREEN_RESULT_OK        0
#define NAR_OFFSCREEN_RESULT_NO_VULKAN 1

typedef struct nar_vulkan_offscreen_image nar_vulkan_offscreen_image;

NAR_EXPORT nar_vulkan_offscreen_image *nar_vulkan_offscreen_image_create(
        nar_vulkan_session *session, uint32_t width, uint32_t height);
NAR_EXPORT void nar_vulkan_offscreen_image_destroy(
        nar_vulkan_session *session, nar_vulkan_offscreen_image *image);

/* Begin a render pass for one offscreen frame.  On success *out_command_buffer
 * receives a VkCommandBuffer in the recording state with the render pass already
 * started.  May be cast to void* for external draw-call recording. */
NAR_EXPORT int32_t nar_vulkan_offscreen_render_begin(
        nar_vulkan_session *session,
        nar_vulkan_offscreen_image *image,
        void **out_command_buffer);

/* End the render pass, copy the image to the readback buffer, and submit.
 * Does NOT wait for completion; call nar_vulkan_offscreen_readback for that. */
NAR_EXPORT int32_t nar_vulkan_offscreen_render_end(
        nar_vulkan_session *session,
        nar_vulkan_offscreen_image *image);

/* Wait for the last submission to finish, then copy pixels into dst.
 * capacity must be >= width * height * 4.  Pixel format is VK_FORMAT_B8G8R8A8_UNORM. */
NAR_EXPORT int32_t nar_vulkan_offscreen_readback(
        nar_vulkan_session *session,
        nar_vulkan_offscreen_image *image,
        void *dst, uint64_t capacity);

/*
 * Minimal optional Vulkan buffer resource ABI.
 *
 * An opaque vertex buffer backed by host-visible (and host-coherent when
 * available) device memory.  The caller never touches Vulkan handles directly;
 * all VkBuffer/VkDeviceMemory ownership stays inside the opaque struct.
 * Uploads map the backing memory, copy, and unmap.  No pipelines, command
 * buffers, or draw binding are exposed by this ABI; it only owns the buffer
 * resource.
 *
 * Typical usage:
 *
 *   nar_vulkan_session *s = nar_vulkan_session_create(NAR_VULKAN_MODE_OFFSCREEN,w,h);
 *   nar_vulkan_buffer *vb = nar_vulkan_buffer_create(s, 4096);
 *   nar_vulkan_buffer_upload(s, vb, vertex_data, sizeof(vertex_data));
 *   nar_vulkan_buffer_destroy(s, vb);
 *   nar_vulkan_session_destroy(s);
 *
 * Returns NAR_VULKAN_BUFFER_RESULT_OK on success, NAR_VULKAN_BUFFER_RESULT_NO_VULKAN
 * when no Vulkan device is present, and a negative value on hard error.
 */

#define NAR_VULKAN_BUFFER_RESULT_OK        0
#define NAR_VULKAN_BUFFER_RESULT_NO_VULKAN 1

typedef struct nar_vulkan_buffer nar_vulkan_buffer;

/* Create a host-visible vertex buffer of the given byte size.  Returns NULL
 * on failure.  The buffer is backed by host-visible memory (preferring
 * host-coherent) and is usable for CPU-side uploads via nar_vulkan_buffer_upload. */
NAR_EXPORT nar_vulkan_buffer *nar_vulkan_buffer_create(
        nar_vulkan_session *session, uint64_t size);

/* Destroy a buffer and free its backing memory.  Safe to call with NULL. */
NAR_EXPORT void nar_vulkan_buffer_destroy(
        nar_vulkan_session *session, nar_vulkan_buffer *buffer);

/* Copy host data into the buffer.  size must be <= the buffer capacity.
 * Returns NAR_VULKAN_BUFFER_RESULT_OK on success, negative on error. */
NAR_EXPORT int32_t nar_vulkan_buffer_upload(
        nar_vulkan_session *session,
        nar_vulkan_buffer *buffer,
        const void *data, uint64_t size);

/* Return the buffer capacity in bytes, or 0 when NULL. */
NAR_EXPORT uint64_t nar_vulkan_buffer_size(const nar_vulkan_buffer *buffer);

/*
 * Shader module + graphics pipeline + command recording ABI.
 *
 * These symbols extend the offscreen rendering ABI toward a real first triangle.
 * A shader module wraps a VkShaderModule created from SPIR-V bytecode.  A pipeline
 * wraps a VkGraphicsPipeline built against the render pass owned by an offscreen
 * image, with a fixed vertex layout ({vec2 position @ location 0, vec3 color @ location 1},
 * 20-byte stride) sufficient for a conventional hello-triangle.  The command
 * recording functions operate on the VkCommandBuffer returned by
 * nar_vulkan_offscreen_render_begin.
 *
 * Returns 0 on success, NAR_VULKAN_PIPELINE_RESULT_NO_VULKAN (1) when no Vulkan
 * loader/device is present, and a negative value on hard error.
 */

#define NAR_VULKAN_PIPELINE_RESULT_OK        0
#define NAR_VULKAN_PIPELINE_RESULT_NO_VULKAN 1

typedef struct nar_vulkan_shader_module nar_vulkan_shader_module;
typedef struct nar_vulkan_pipeline nar_vulkan_pipeline;

/* Create a shader module from SPIR-V bytecode.  code_size is in bytes and must
 * be a multiple of 4.  Returns NULL on failure. */
NAR_EXPORT nar_vulkan_shader_module *nar_vulkan_shader_module_create(
        nar_vulkan_session *session, const void *code, uint64_t code_size);
NAR_EXPORT void nar_vulkan_shader_module_destroy(
        nar_vulkan_session *session, nar_vulkan_shader_module *module);

/* Create a graphics pipeline for the render pass owned by the given offscreen
 * image.  The vertex layout is fixed: one binding with 20-byte stride, two
 * attributes (vec2 position at offset 0 / location 0, vec3 color at offset 8 /
 * location 1).  Returns NULL on failure. */
NAR_EXPORT nar_vulkan_pipeline *nar_vulkan_pipeline_create(
        nar_vulkan_session *session,
        nar_vulkan_offscreen_image *image,
        nar_vulkan_shader_module *vertex_shader,
        nar_vulkan_shader_module *fragment_shader);
NAR_EXPORT void nar_vulkan_pipeline_destroy(
        nar_vulkan_session *session, nar_vulkan_pipeline *pipeline);

/* Record into the VkCommandBuffer returned by nar_vulkan_offscreen_render_begin.
 * command_buffer is the opaque handle obtained from that call.  All return 0 on
 * success, NAR_VULKAN_PIPELINE_RESULT_NO_VULKAN when Vulkan is unavailable, or a
 * negative value on hard error. */
NAR_EXPORT int32_t nar_vulkan_cmd_bind_pipeline(
        nar_vulkan_session *session,
        void *command_buffer,
        nar_vulkan_pipeline *pipeline);
NAR_EXPORT int32_t nar_vulkan_cmd_bind_vertex_buffer(
        nar_vulkan_session *session,
        void *command_buffer,
        nar_vulkan_buffer *buffer, uint64_t offset);
NAR_EXPORT int32_t nar_vulkan_cmd_draw(
        nar_vulkan_session *session,
        void *command_buffer,
        uint32_t vertex_count, uint32_t first_vertex);

/*
 * Mesa installation selection for Unix-family hosts.
 *
 * A Unix-like machine -- Solaris/illumos, the BSDs, Linux, HP-UX, AIX and the like -- often carries
 * several Mesa trees at once: a system default, a ports or pkgsrc tree, and one or more third-party
 * trees unpacked under /opt. Some of those trees are built without Vulkan. The renderer therefore
 * looks through every candidate tree, ignores the ones with no usable Vulkan ICD manifest, and
 * picks the newest versioned tree that does provide one. The rule and the environment variables are
 * the same on all of those systems; only the default locations differ.
 *
 * Candidates come from NATIVE_ACCELERATOR_MESA_PREFIX and NATIVE_ACCELERATOR_MESA_SEARCH_PATH.
 * Both name roots, and a root contributes itself plus its immediate subdirectories; the prefixes
 * are examined first and the platform defaults follow. Nothing here is tied to a driver vendor.
 *
 * nar_vulkan_mesa_prefix() reports the selected tree; nar_vulkan_mesa_loader() reports the Vulkan
 * loader inside it when that tree ships one. Both return the string length and may be called with
 * (NULL, 0) to query the length.
 */
NAR_EXPORT uint64_t nar_vulkan_mesa_prefix(char *dst, uint64_t capacity);
NAR_EXPORT uint64_t nar_vulkan_mesa_loader(char *dst, uint64_t capacity);

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

/*
 * Client-renderer runtime policy: evidence, not an operating-system allow-list.
 *
 * Native Accelerator never decides the client renderer from the OS name. It asks two runtime questions:
 *
 *   1. Does this computer actually support Vulkan? Answered by opening a Vulkan loader and enumerating
 *      at least one physical device. A Mesa, vendor, or translation-layer build that was compiled
 *      without usable Vulkan support answers "no" even on an operating system where Vulkan is common,
 *      and a machine that does provide Vulkan answers "yes" even if that is unusual for its OS.
 *
 *   2. Which graphics backend is Minecraft itself using? Answered by reading options.txt in the
 *      Minecraft directory, the same file the game writes. Native Accelerator has no OpenGL surface
 *      path, so when Minecraft runs a non-Vulkan backend the client renderer rewrite has no benefit.
 *
 * NAR_RENDERER_ROLE_SERVER_ONLY therefore means "the evidence says the Vulkan client renderer gives no
 * benefit here", not "unsupported operating system". On a server-only host the client still starts,
 * plays normally, and keeps the full native compute accelerator, and the renderer companion library
 * still builds and remains usable for headless/dedicated-server workloads. Callers may always force a
 * decision explicitly.
 */
#define NAR_RENDERER_ROLE_CLIENT      0u
#define NAR_RENDERER_ROLE_SERVER_ONLY 1u
#define NAR_RENDERER_ROLE_AUTO        2u

/* Evidence bits returned by nar_renderer_platform_evidence(). */
#define NAR_RENDERER_EVIDENCE_VULKAN_LOADER        (UINT32_C(1) << 0)
#define NAR_RENDERER_EVIDENCE_VULKAN_DEVICE        (UINT32_C(1) << 1)
#define NAR_RENDERER_EVIDENCE_MINECRAFT_OPTIONS    (UINT32_C(1) << 2)
#define NAR_RENDERER_EVIDENCE_MINECRAFT_VULKAN     (UINT32_C(1) << 3)
#define NAR_RENDERER_EVIDENCE_MINECRAFT_NON_VULKAN (UINT32_C(1) << 4)
#define NAR_RENDERER_EVIDENCE_OVERRIDE             (UINT32_C(1) << 5)

/* Resolved client-renderer role for this machine, honouring any explicit override. */
NAR_EXPORT uint32_t nar_renderer_platform_role(void);
/* 1 when the evidence supports selecting the client Vulkan renderer. */
NAR_EXPORT int32_t nar_renderer_client_eligible(void);
/* The individual evidence bits that produced the role. */
NAR_EXPORT uint32_t nar_renderer_platform_evidence(void);
/* Human-readable summary, for example: vulkan=device-ready minecraft=vulkan (client-eligible). */
NAR_EXPORT uint64_t nar_renderer_platform_name(char *dst, uint64_t capacity);

/*
 * Minecraft options.txt discovery. Pass a specific path (for example the real game directory file) to
 * make the game-backend evidence authoritative; pass NULL to return to automatic discovery. The Java
 * integration passes the resolved game directory; C-only callers may rely on automatic discovery, which
 * checks NATIVE_ACCELERATOR_MINECRAFT_OPTIONS, the working directory, ./run, MINECRAFT_HOME, and the
 * standard per-user Minecraft locations.
 */
NAR_EXPORT void nar_renderer_set_options_path(const char *path);
/* Path currently used for the Minecraft backend evidence, or an empty string when none was found. */
NAR_EXPORT uint64_t nar_renderer_options_path(char *dst, uint64_t capacity);
/* Override the role: NAR_RENDERER_ROLE_AUTO (evidence), CLIENT, or SERVER_ONLY. */
NAR_EXPORT void nar_renderer_set_role_override(uint32_t role);

/*
 * Point the Vulkan loader at the selected Mesa tree. The renderer probe calls this before opening a
 * loader so the newest Vulkan-enabled Mesa tree is the one actually used. Existing VK_DRIVER_FILES
 * and VK_ICD_FILENAMES values are never overwritten.
 */
NAR_EXPORT void nar_vulkan_mesa_apply_selection(void);

/* Internal job system used by the renderer implementation. */
typedef void (*nar_parallel_fn)(void *userdata, uint32_t index);
int32_t nar_parallel_for(nar_context *context, uint32_t count, nar_parallel_fn function, void *userdata);

#ifdef __cplusplus
}
#endif

#endif
