#include "native_accelerator_renderer.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* This file intentionally carries only the Vulkan ABI records needed by the
 * conservative offscreen session. The project does not require a Vulkan SDK
 * at build time; all entry points are resolved from the loader at runtime. */
typedef int32_t nar_vk_result;
typedef uint32_t nar_vk_bool32;
typedef void *nar_vk_instance;
typedef void *nar_vk_physical_device;
typedef void *nar_vk_device;
typedef void *nar_vk_queue;
typedef void *nar_vk_command_pool;
typedef void *nar_vk_surface;
typedef void *nar_vk_swapchain;
typedef void *nar_vk_semaphore;
typedef void *nar_vk_fence;
typedef void *nar_vk_image;
typedef void *nar_vk_device_memory;
typedef void *nar_vk_image_view;
typedef void *nar_vk_render_pass;
typedef void *nar_vk_framebuffer;
typedef void *nar_vk_command_buffer;
typedef void *nar_vk_buffer;

typedef struct nar_vk_application_info {
    uint32_t s_type;
    const void *p_next;
    const char *application_name;
    uint32_t application_version;
    const char *engine_name;
    uint32_t engine_version;
    uint32_t api_version;
} nar_vk_application_info;

typedef struct nar_vk_instance_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    const nar_vk_application_info *application_info;
    uint32_t enabled_layer_count;
    const char *const *enabled_layer_names;
    uint32_t enabled_extension_count;
    const char *const *enabled_extension_names;
} nar_vk_instance_create_info;

typedef struct nar_vk_device_queue_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    uint32_t queue_family_index;
    uint32_t queue_count;
    const float *queue_priorities;
} nar_vk_device_queue_create_info;

typedef struct nar_vk_device_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    uint32_t queue_create_info_count;
    const nar_vk_device_queue_create_info *queue_create_infos;
    uint32_t enabled_layer_count;
    const char *const *enabled_layer_names;
    uint32_t enabled_extension_count;
    const char *const *enabled_extension_names;
    const void *enabled_features;
} nar_vk_device_create_info;

typedef struct nar_vk_xlib_surface_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    void *dpy;
    uint64_t window;
} nar_vk_xlib_surface_create_info;

typedef struct nar_vk_surface_capabilities {
    uint32_t min_image_count;
    uint32_t max_image_count;
    uint32_t current_extent_width;
    uint32_t current_extent_height;
    uint32_t min_image_extent_width;
    uint32_t min_image_extent_height;
    uint32_t max_image_extent_width;
    uint32_t max_image_extent_height;
    uint32_t max_image_array_layers;
    uint32_t supported_transforms;
    uint32_t current_transform;
    uint32_t supported_composite_alpha;
    uint32_t supported_usage_flags;
} nar_vk_surface_capabilities;

typedef struct nar_vk_surface_format { uint32_t format; uint32_t color_space; } nar_vk_surface_format;
typedef struct nar_vk_swapchain_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    nar_vk_surface surface;
    uint32_t min_image_count;
    uint32_t image_format;
    uint32_t image_color_space;
    uint32_t image_extent_width;
    uint32_t image_extent_height;
    uint32_t image_array_layers;
    uint32_t image_usage;
    uint32_t image_sharing_mode;
    uint32_t queue_family_index_count;
    const uint32_t *queue_family_indices;
    uint32_t pre_transform;
    uint32_t composite_alpha;
    uint32_t present_mode;
    uint32_t clipped;
    nar_vk_swapchain old_swapchain;
} nar_vk_swapchain_create_info;

typedef struct nar_vk_present_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t wait_semaphore_count;
    const nar_vk_semaphore *wait_semaphores;
    uint32_t swapchain_count;
    const nar_vk_swapchain *swapchains;
    const uint32_t *image_indices;
    const nar_vk_result *results;
} nar_vk_present_info;

typedef struct nar_vk_command_pool_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    uint32_t queue_family_index;
} nar_vk_command_pool_create_info;

typedef struct nar_vk_extension_properties {
    char name[256];
    uint32_t spec_version;
} nar_vk_extension_properties;

typedef struct nar_vk_queue_family_properties {
    uint32_t queue_flags;
    uint32_t queue_count;
    uint32_t timestamp_valid_bits;
    uint32_t min_image_transfer_granularity[3];
} nar_vk_queue_family_properties;

typedef struct nar_vk_physical_device_properties {
    uint32_t api_version;
    uint32_t driver_version;
    uint32_t vendor_id;
    uint32_t device_id;
    uint32_t device_type;
    char device_name[256];
    unsigned char rest[4096 - 276];
} nar_vk_physical_device_properties;

typedef nar_vk_result (*nar_vk_create_instance_fn)(const nar_vk_instance_create_info *, const void *, nar_vk_instance *);
typedef nar_vk_result (*nar_vk_enumerate_instance_extension_properties_fn)(const char *, uint32_t *, nar_vk_extension_properties *);
typedef void (*nar_vk_destroy_instance_fn)(nar_vk_instance, const void *);
typedef nar_vk_result (*nar_vk_enumerate_physical_devices_fn)(nar_vk_instance, uint32_t *, nar_vk_physical_device *);
typedef void *(*nar_vk_get_instance_proc_addr_fn)(nar_vk_instance, const char *);
typedef void *(*nar_vk_get_device_proc_addr_fn)(nar_vk_device, const char *);
typedef void (*nar_vk_get_physical_device_properties_fn)(nar_vk_physical_device, nar_vk_physical_device_properties *);
typedef void (*nar_vk_get_physical_device_queue_family_properties_fn)(nar_vk_physical_device, uint32_t *, nar_vk_queue_family_properties *);
typedef nar_vk_result (*nar_vk_enumerate_device_extension_properties_fn)(nar_vk_physical_device, const char *, uint32_t *, nar_vk_extension_properties *);
typedef nar_vk_result (*nar_vk_create_device_fn)(nar_vk_physical_device, const nar_vk_device_create_info *, const void *, nar_vk_device *);
typedef void (*nar_vk_destroy_device_fn)(nar_vk_device, const void *);
typedef nar_vk_result (*nar_vk_create_xlib_surface_fn)(nar_vk_instance, const nar_vk_xlib_surface_create_info *, const void *, nar_vk_surface *);
typedef void (*nar_vk_destroy_surface_fn)(nar_vk_instance, nar_vk_surface, const void *);
typedef nar_vk_result (*nar_vk_get_surface_support_fn)(nar_vk_physical_device, uint32_t, nar_vk_surface, nar_vk_bool32 *);
typedef nar_vk_result (*nar_vk_get_surface_capabilities_fn)(nar_vk_physical_device, nar_vk_surface, nar_vk_surface_capabilities *);
typedef nar_vk_result (*nar_vk_get_surface_formats_fn)(nar_vk_physical_device, nar_vk_surface, uint32_t *, nar_vk_surface_format *);
typedef nar_vk_result (*nar_vk_get_surface_present_modes_fn)(nar_vk_physical_device, nar_vk_surface, uint32_t *, uint32_t *);
typedef nar_vk_result (*nar_vk_create_swapchain_fn)(nar_vk_device, const nar_vk_swapchain_create_info *, const void *, nar_vk_swapchain *);
typedef void (*nar_vk_destroy_swapchain_fn)(nar_vk_device, nar_vk_swapchain, const void *);
typedef nar_vk_result (*nar_vk_get_swapchain_images_fn)(nar_vk_device, nar_vk_swapchain, uint32_t *, nar_vk_image *);
typedef nar_vk_result (*nar_vk_acquire_next_image_fn)(nar_vk_device, nar_vk_swapchain, uint64_t, nar_vk_semaphore, nar_vk_fence, uint32_t *);
typedef nar_vk_result (*nar_vk_queue_present_fn)(nar_vk_queue, const nar_vk_present_info *);
typedef void (*nar_vk_get_device_queue_fn)(nar_vk_device, uint32_t, uint32_t, nar_vk_queue *);
typedef nar_vk_result (*nar_vk_create_command_pool_fn)(nar_vk_device, const nar_vk_command_pool_create_info *, const void *, nar_vk_command_pool *);
typedef void (*nar_vk_destroy_command_pool_fn)(nar_vk_device, nar_vk_command_pool, const void *);

/* ---------- Additional Vulkan handle types for offscreen rendering ---------- */

/* Image creation structs */
typedef struct nar_vk_extent3d { uint32_t width, height, depth; } nar_vk_extent3d;
typedef struct nar_vk_image_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    uint32_t image_type;      /* 1 = VK_IMAGE_TYPE_2D */
    uint32_t format;          /* VK_FORMAT_B8G8R8A8_UNORM = 44 */
    nar_vk_extent3d extent;
    uint32_t mip_levels;
    uint32_t array_layers;
    uint32_t samples;         /* 1 = VK_SAMPLE_COUNT_1_BIT */
    uint32_t tiling;          /* 0 = VK_IMAGE_TILING_OPTIMAL */
    uint32_t usage;
    uint32_t sharing_mode;    /* 0 = VK_SHARING_MODE_EXCLUSIVE */
    uint32_t queue_family_index_count;
    const uint32_t *queue_family_indices;
    uint32_t initial_layout;  /* 0 = VK_IMAGE_LAYOUT_UNDEFINED */
} nar_vk_image_create_info;

/* Memory allocation */
typedef struct nar_vk_memory_requirements { uint64_t size, alignment; uint32_t memory_type_bits; } nar_vk_memory_requirements;
typedef struct nar_vk_memory_allocate_info {
    uint32_t s_type;
    const void *p_next;
    uint64_t allocation_size;
    uint32_t memory_type_index;
} nar_vk_memory_allocate_info;

typedef struct nar_vk_physical_device_memory_properties {
    uint32_t memory_type_count;
    struct { uint32_t property_flags; uint32_t heap_index; } memory_types[32];
    uint32_t memory_heap_count;
    struct { uint64_t size; uint32_t flags; } memory_heaps[16];
} nar_vk_physical_device_memory_properties;

/* Image view */
typedef struct nar_vk_component_mapping { uint32_t r, g, b, a; } nar_vk_component_mapping;
typedef struct nar_vk_image_subresource_range {
    uint32_t aspect_mask;
    uint32_t base_mip_level;
    uint32_t level_count;
    uint32_t base_array_layer;
    uint32_t layer_count;
} nar_vk_image_subresource_range;
typedef struct nar_vk_image_view_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    nar_vk_image image;
    uint32_t view_type;   /* 1 = VK_IMAGE_VIEW_TYPE_2D */
    uint32_t format;
    nar_vk_component_mapping components;
    nar_vk_image_subresource_range subresource_range;
} nar_vk_image_view_create_info;

/* Render pass */
typedef struct nar_vk_attachment_description {
    uint32_t flags;
    uint32_t format;
    uint32_t samples;
    uint32_t load_op;
    uint32_t store_op;
    uint32_t stencil_load_op;
    uint32_t stencil_store_op;
    uint32_t initial_layout;
    uint32_t final_layout;
} nar_vk_attachment_description;
typedef struct nar_vk_attachment_reference { uint32_t attachment; uint32_t layout; } nar_vk_attachment_reference;
typedef struct nar_vk_subpass_description {
    uint32_t flags;
    uint32_t pipeline_bind_point; /* 0 = VK_PIPELINE_BIND_POINT_GRAPHICS */
    uint32_t input_attachment_count;
    const nar_vk_attachment_reference *input_attachments;
    uint32_t color_attachment_count;
    const nar_vk_attachment_reference *color_attachments;
    const nar_vk_attachment_reference *resolve_attachments;
    const nar_vk_attachment_reference *depth_stencil_attachment;
    uint32_t preserve_attachment_count;
    const uint32_t *preserve_attachments;
} nar_vk_subpass_description;
typedef struct nar_vk_render_pass_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    uint32_t attachment_count;
    const nar_vk_attachment_description *attachments;
    uint32_t subpass_count;
    const nar_vk_subpass_description *subpasses;
    uint32_t dependency_count;
    const void *dependencies;
} nar_vk_render_pass_create_info;

/* Framebuffer */
typedef struct nar_vk_framebuffer_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    nar_vk_render_pass render_pass;
    uint32_t attachment_count;
    const nar_vk_image_view *attachments;
    uint32_t width, height, layers;
} nar_vk_framebuffer_create_info;

/* Command buffer */
typedef struct nar_vk_command_buffer_allocate_info {
    uint32_t s_type;
    const void *p_next;
    nar_vk_command_pool command_pool;
    uint32_t level;   /* 0 = VK_COMMAND_BUFFER_LEVEL_PRIMARY */
    uint32_t command_buffer_count;
} nar_vk_command_buffer_allocate_info;
typedef struct nar_vk_command_buffer_begin_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    const void *inheritance_info;
} nar_vk_command_buffer_begin_info;

/* Render pass begin */
typedef struct nar_vk_rect2d { int32_t x, y; uint32_t width, height; } nar_vk_rect2d;
typedef struct nar_vk_clear_value { float color[4]; } nar_vk_clear_value;
typedef struct nar_vk_render_pass_begin_info {
    uint32_t s_type;
    const void *p_next;
    nar_vk_render_pass render_pass;
    nar_vk_framebuffer framebuffer;
    nar_vk_rect2d render_area;
    uint32_t clear_value_count;
    const nar_vk_clear_value *clear_values;
} nar_vk_render_pass_begin_info;

/* Fence */
typedef struct nar_vk_fence_create_info { uint32_t s_type; const void *p_next; uint32_t flags; } nar_vk_fence_create_info;
typedef nar_vk_fence_create_info nar_vk_semaphore_create_info;
typedef struct nar_vk_submit_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t wait_semaphore_count;
    const void *wait_semaphores;
    const uint32_t *wait_dst_stage_mask;
    uint32_t command_buffer_count;
    const nar_vk_command_buffer *command_buffers;
    uint32_t signal_semaphore_count;
    const void *signal_semaphores;
} nar_vk_submit_info;

/* Buffer (for readback) */
typedef struct nar_vk_buffer_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    uint64_t size;
    uint32_t usage;
    uint32_t sharing_mode;
    uint32_t queue_family_index_count;
    const uint32_t *queue_family_indices;
} nar_vk_buffer_create_info;

/* Image memory barrier */
typedef struct nar_vk_image_memory_barrier {
    uint32_t s_type;
    const void *p_next;
    uint32_t src_access_mask;
    uint32_t dst_access_mask;
    uint32_t old_layout;
    uint32_t new_layout;
    uint32_t src_queue_family_index; /* VK_QUEUE_FAMILY_IGNORED = ~0u */
    uint32_t dst_queue_family_index;
    nar_vk_image image;
    nar_vk_image_subresource_range subresource_range;
} nar_vk_image_memory_barrier;

typedef struct nar_vk_buffer_memory_barrier {
    uint32_t s_type;
    const void *p_next;
    uint32_t src_access_mask;
    uint32_t dst_access_mask;
    uint32_t src_queue_family_index;
    uint32_t dst_queue_family_index;
    nar_vk_buffer buffer;
    uint64_t offset;
    uint64_t size;
} nar_vk_buffer_memory_barrier;

/* Buffer image copy */
typedef struct nar_vk_image_subresource_layers {
    uint32_t aspect_mask;
    uint32_t mip_level;
    uint32_t base_array_layer;
    uint32_t layer_count;
} nar_vk_image_subresource_layers;
typedef struct nar_vk_buffer_image_copy {
    uint64_t buffer_offset;
    uint32_t buffer_row_length;
    uint32_t buffer_image_height;
    nar_vk_image_subresource_layers image_subresource;
    int32_t image_offset_x, image_offset_y, image_offset_z;
    nar_vk_extent3d image_extent;
} nar_vk_buffer_image_copy;

/* ---------- Function pointer typedefs for offscreen rendering ---------- */
typedef nar_vk_result (*nar_vk_create_image_fn)(nar_vk_device, const nar_vk_image_create_info *, const void *, nar_vk_image *);
typedef void (*nar_vk_destroy_image_fn)(nar_vk_device, nar_vk_image, const void *);
typedef void (*nar_vk_get_image_memory_requirements_fn)(nar_vk_device, nar_vk_image, nar_vk_memory_requirements *);
typedef void (*nar_vk_get_physical_device_memory_properties_fn)(nar_vk_physical_device, nar_vk_physical_device_memory_properties *);
typedef nar_vk_result (*nar_vk_allocate_memory_fn)(nar_vk_device, const nar_vk_memory_allocate_info *, const void *, nar_vk_device_memory *);
typedef void (*nar_vk_free_memory_fn)(nar_vk_device, nar_vk_device_memory, const void *);
typedef nar_vk_result (*nar_vk_bind_image_memory_fn)(nar_vk_device, nar_vk_image, nar_vk_device_memory, uint64_t);
typedef nar_vk_result (*nar_vk_create_image_view_fn)(nar_vk_device, const nar_vk_image_view_create_info *, const void *, nar_vk_image_view *);
typedef void (*nar_vk_destroy_image_view_fn)(nar_vk_device, nar_vk_image_view, const void *);
typedef nar_vk_result (*nar_vk_create_render_pass_fn)(nar_vk_device, const nar_vk_render_pass_create_info *, const void *, nar_vk_render_pass *);
typedef void (*nar_vk_destroy_render_pass_fn)(nar_vk_device, nar_vk_render_pass, const void *);
typedef nar_vk_result (*nar_vk_create_framebuffer_fn)(nar_vk_device, const nar_vk_framebuffer_create_info *, const void *, nar_vk_framebuffer *);
typedef void (*nar_vk_destroy_framebuffer_fn)(nar_vk_device, nar_vk_framebuffer, const void *);
typedef nar_vk_result (*nar_vk_allocate_command_buffers_fn)(nar_vk_device, const nar_vk_command_buffer_allocate_info *, nar_vk_command_buffer *);
typedef void (*nar_vk_free_command_buffers_fn)(nar_vk_device, nar_vk_command_pool, uint32_t, const nar_vk_command_buffer *);
typedef nar_vk_result (*nar_vk_begin_command_buffer_fn)(nar_vk_command_buffer, const nar_vk_command_buffer_begin_info *);
typedef nar_vk_result (*nar_vk_end_command_buffer_fn)(nar_vk_command_buffer);
typedef void (*nar_vk_cmd_begin_render_pass_fn)(nar_vk_command_buffer, const nar_vk_render_pass_begin_info *, uint32_t);
typedef void (*nar_vk_cmd_end_render_pass_fn)(nar_vk_command_buffer);
typedef void (*nar_vk_cmd_pipeline_barrier_fn)(nar_vk_command_buffer, uint32_t, uint32_t, uint32_t, uint32_t, const void *, uint32_t, const void *, uint32_t, const nar_vk_image_memory_barrier *);
typedef void (*nar_vk_cmd_copy_image_to_buffer_fn)(nar_vk_command_buffer, nar_vk_image, uint32_t, nar_vk_buffer, uint32_t, const nar_vk_buffer_image_copy *);
typedef nar_vk_result (*nar_vk_create_fence_fn)(nar_vk_device, const nar_vk_fence_create_info *, const void *, nar_vk_fence *);
typedef nar_vk_result (*nar_vk_create_semaphore_fn)(nar_vk_device, const nar_vk_semaphore_create_info *, const void *, nar_vk_semaphore *);
typedef void (*nar_vk_destroy_fence_fn)(nar_vk_device, nar_vk_fence, const void *);
typedef void (*nar_vk_destroy_semaphore_fn)(nar_vk_device, nar_vk_semaphore, const void *);
typedef nar_vk_result (*nar_vk_reset_fences_fn)(nar_vk_device, uint32_t, const nar_vk_fence *);
typedef nar_vk_result (*nar_vk_wait_for_fences_fn)(nar_vk_device, uint32_t, const nar_vk_fence *, uint32_t, uint64_t);
typedef nar_vk_result (*nar_vk_queue_submit_fn)(nar_vk_queue, uint32_t, const nar_vk_submit_info *, nar_vk_fence);
typedef nar_vk_result (*nar_vk_device_wait_idle_fn)(nar_vk_device);
typedef nar_vk_result (*nar_vk_create_buffer_fn)(nar_vk_device, const nar_vk_buffer_create_info *, const void *, nar_vk_buffer *);
typedef void (*nar_vk_destroy_buffer_fn)(nar_vk_device, nar_vk_buffer, const void *);
typedef void (*nar_vk_get_buffer_memory_requirements_fn)(nar_vk_device, nar_vk_buffer, nar_vk_memory_requirements *);
typedef nar_vk_result (*nar_vk_bind_buffer_memory_fn)(nar_vk_device, nar_vk_buffer, nar_vk_device_memory, uint64_t);
typedef nar_vk_result (*nar_vk_map_memory_fn)(nar_vk_device, nar_vk_device_memory, uint64_t, uint64_t, uint32_t, void **);
typedef void (*nar_vk_unmap_memory_fn)(nar_vk_device, nar_vk_device_memory);
typedef nar_vk_result (*nar_vk_reset_command_buffer_fn)(nar_vk_command_buffer, uint32_t);

enum {
    NAR_VK_SUCCESS = 0,
    NAR_VK_INCOMPLETE = 5,
    NAR_VK_STRUCTURE_TYPE_APPLICATION_INFO = 0,
    NAR_VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO = 1,
    NAR_VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO = 2,
    NAR_VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO = 3,
    NAR_VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO = 1000004000,
    NAR_VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO = 1000001000,
    NAR_VK_STRUCTURE_TYPE_PRESENT_INFO = 1000001001,
    NAR_VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO = 39,
    NAR_VK_QUEUE_GRAPHICS_BIT = 0x00000001u,
    NAR_VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT = 0x00000002u,
    NAR_VK_MAX_EXTENSION_PROPERTIES = 256,
    NAR_VK_MAX_QUEUE_FAMILIES = 128,
    /* Offscreen rendering constants */
    NAR_VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO            = 14,
    NAR_VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO       = 15,
    NAR_VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO      = 38,
    NAR_VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO      = 37,
    NAR_VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO = 40,
    NAR_VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO    = 42,
    NAR_VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO       = 43,
    NAR_VK_STRUCTURE_TYPE_FENCE_CREATE_INFO            = 8,
    NAR_VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO        = 9,
    NAR_VK_STRUCTURE_TYPE_SUBMIT_INFO                  = 4,
    NAR_VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO           = 12,
    NAR_VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO         = 5,
    NAR_VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER         = 45,
    NAR_VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER        = 44,
    /* VkFormat */
    NAR_VK_FORMAT_B8G8R8A8_UNORM = 44,
    /* VkImageType */
    NAR_VK_IMAGE_TYPE_2D = 1,
    /* VkImageTiling */
    NAR_VK_IMAGE_TILING_OPTIMAL = 0,
    NAR_VK_IMAGE_TILING_LINEAR  = 1,
    /* VkImageUsageFlags */
    NAR_VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT = 0x00000010,
    NAR_VK_IMAGE_USAGE_TRANSFER_SRC_BIT     = 0x00000001,
    /* VkSampleCountFlagBits */
    NAR_VK_SAMPLE_COUNT_1_BIT = 1,
    /* VkImageViewType */
    NAR_VK_IMAGE_VIEW_TYPE_2D = 1,
    /* VkImageAspectFlags */
    NAR_VK_IMAGE_ASPECT_COLOR_BIT = 0x00000001,
    /* VkAttachmentLoadOp */
    NAR_VK_ATTACHMENT_LOAD_OP_CLEAR = 1,
    /* VkAttachmentStoreOp */
    NAR_VK_ATTACHMENT_STORE_OP_STORE = 0,
    /* VkImageLayout */
    NAR_VK_IMAGE_LAYOUT_UNDEFINED                   = 0,
    NAR_VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL    = 2,
    NAR_VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL        = 6,
    NAR_VK_IMAGE_LAYOUT_PRESENT_SRC_KHR              = 1000001002,
    NAR_VK_IMAGE_LAYOUT_GENERAL                     = 1,
    /* VkPipelineBindPoint */
    NAR_VK_PIPELINE_BIND_POINT_GRAPHICS = 0,
    /* VkCommandBufferLevel */
    NAR_VK_COMMAND_BUFFER_LEVEL_PRIMARY = 0,
    /* VkSubpassContents */
    NAR_VK_SUBPASS_CONTENTS_INLINE = 0,
    /* VkCommandBufferUsageFlags */
    NAR_VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT = 0x00000001,
    /* VkPipelineStageFlags */
    NAR_VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT              = 0x00000001,
    NAR_VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT  = 0x00000400,
    NAR_VK_PIPELINE_STAGE_TRANSFER_BIT                 = 0x00001000,
    NAR_VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT           = 0x00002000,
    NAR_VK_PIPELINE_STAGE_HOST_BIT                     = 0x00004000,
    /* VkAccessFlags */
    NAR_VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT = 0x00000100,
    NAR_VK_ACCESS_TRANSFER_READ_BIT          = 0x00000800,
    NAR_VK_ACCESS_TRANSFER_WRITE_BIT         = 0x00001000,
    NAR_VK_ACCESS_HOST_READ_BIT              = 0x00002000,
    /* VkBufferUsageFlags */
    NAR_VK_BUFFER_USAGE_TRANSFER_DST_BIT = 0x00000002,
    NAR_VK_BUFFER_USAGE_VERTEX_BUFFER_BIT = 0x00000080,
    /* VkMemoryPropertyFlags */
    NAR_VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT  = 0x00000001,
    NAR_VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT  = 0x00000002,
    NAR_VK_MEMORY_PROPERTY_HOST_COHERENT_BIT = 0x00000004,
    /* VkQueueFamilyIgnored */
    NAR_VK_QUEUE_FAMILY_IGNORED = 0xFFFFFFFFu,
    NAR_VK_IMAGE_USAGE_TRANSFER_DST_BIT = 0x00000002,
    NAR_VK_SHARING_MODE_EXCLUSIVE = 0,
    NAR_VK_SHARING_MODE_CONCURRENT = 1,
    NAR_VK_COMPOSITE_ALPHA_OPAQUE_BIT = 0x00000001,
    NAR_VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT = 0x00000002,
    NAR_VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT = 0x00000004,
    NAR_VK_COMPOSITE_ALPHA_INHERIT_BIT = 0x00000008,
    NAR_VK_FENCE_CREATE_SIGNALED_BIT = 0x00000001,
    NAR_VK_SURFACE_TRANSFORM_IDENTITY_BIT = 0x00000001,
    NAR_VK_PRESENT_MODE_FIFO = 2,
    NAR_VK_PRESENT_MODE_MAILBOX = 1,
    NAR_VK_COLOR_SPACE_SRGB_NONLINEAR = 0,
    NAR_VK_FORMAT_B8G8R8A8_SRGB = 50,
    NAR_VK_FORMAT_R8G8B8A8_SRGB = 43,
    NAR_VK_ERROR_OUT_OF_DATE = -1000001004,
    NAR_VK_SUBOPTIMAL = 1000001003,
    NAR_VK_ERROR_SURFACE_LOST = -1000000000
};

struct nar_vulkan_session {
    uint32_t mode;
    uint32_t width;
    uint32_t height;
    uint32_t tier;
    uint64_t features;
    uint32_t graphics_queue_family;
    uint32_t present_queue_family;
    nar_vk_instance instance;
    nar_vk_physical_device physical_device;
    nar_vk_device device;
    nar_vk_queue graphics_queue;
    nar_vk_queue present_queue;
    nar_vk_command_pool command_pool;
    nar_vk_surface surface;
    nar_vk_destroy_instance_fn destroy_instance;
    nar_vk_destroy_device_fn destroy_device;
    nar_vk_device_wait_idle_fn device_wait_idle;
    nar_vk_destroy_command_pool_fn destroy_command_pool;
    nar_vk_destroy_surface_fn destroy_surface;
    nar_vk_get_instance_proc_addr_fn gipa;
    /* Cached device-level function pointers used by offscreen and WSI paths */
    nar_vk_get_device_proc_addr_fn gdpa;
    nar_vk_create_swapchain_fn create_swapchain;
    nar_vk_destroy_swapchain_fn destroy_swapchain;
    nar_vk_get_swapchain_images_fn get_swapchain_images;
    nar_vk_acquire_next_image_fn acquire_next_image;
    nar_vk_queue_present_fn queue_present;
    struct nar_vulkan_swapchain *swapchains;
    char device_name[256];
    char error[512];
};

#define NAR_VK_MAX_SWAPCHAIN_IMAGES 16u
#define NAR_VK_MAX_FRAMES_IN_FLIGHT 2u

struct nar_vulkan_swapchain_frame {
    nar_vk_command_buffer command_buffer;
    nar_vk_semaphore image_available;
    nar_vk_semaphore render_finished;
    nar_vk_fence fence;
};

struct nar_vulkan_swapchain {
    nar_vk_swapchain handle;
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint32_t color_space;
    uint32_t present_mode;
    uint32_t image_count;
    nar_vk_image images[NAR_VK_MAX_SWAPCHAIN_IMAGES];
    uint32_t image_view_count;
    nar_vk_image_view image_views[NAR_VK_MAX_SWAPCHAIN_IMAGES];
    nar_vk_render_pass render_pass;
    nar_vk_framebuffer framebuffers[NAR_VK_MAX_SWAPCHAIN_IMAGES];
    struct nar_vulkan_swapchain_frame frames[NAR_VK_MAX_FRAMES_IN_FLIGHT];
    uint32_t frame_index;
    uint32_t acquired_image;
    int acquired;
    struct nar_vulkan_swapchain *next;
};

/* Opaque offscreen rendering resource bundle */
struct nar_vulkan_offscreen_image {
    uint32_t width;
    uint32_t height;
    /* Device-local color attachment */
    nar_vk_image image;
    nar_vk_device_memory image_memory;
    nar_vk_image_view image_view;
    nar_vk_render_pass render_pass;
    nar_vk_framebuffer framebuffer;
    /* Host-visible readback buffer */
    nar_vk_buffer readback_buffer;
    nar_vk_device_memory readback_memory;
    uint64_t readback_size;
    /* Per-frame command buffer and fence */
    nar_vk_command_buffer command_buffer;
    nar_vk_fence fence;
    int fence_submitted; /* 1 after render_end, reset after readback */
    /* Cached function pointers (borrowed from session) */
    nar_vk_get_device_proc_addr_fn gdpa;
    nar_vk_device device;
};

extern int nar_vulkan_wsi_create_platform_surface(
        uint32_t platform,
        nar_vk_get_instance_proc_addr_fn gipa,
        nar_vk_instance instance,
        const nar_vulkan_wsi_window *window,
        nar_vk_surface *out_surface,
        char *error,
        size_t error_capacity);

static void destroy_swapchain_resources(nar_vulkan_session *session,
                                         nar_vulkan_swapchain *swapchain,
                                         int free_struct);

static void copy_text(char *dst, size_t capacity, const char *text) {
    if (capacity == 0) return;
    if (text == NULL) text = "";
    (void)snprintf(dst, capacity, "%s", text);
}

static int has_instance_extension(nar_vk_enumerate_instance_extension_properties_fn enumerate,
                                  const char *wanted) {
    if (enumerate == NULL || wanted == NULL) return 0;
    uint32_t count = 0;
    nar_vk_result first = enumerate(NULL, &count, NULL);
    if ((first != NAR_VK_SUCCESS && first != NAR_VK_INCOMPLETE) || count == 0) return 0;
    if (count > NAR_VK_MAX_EXTENSION_PROPERTIES) count = NAR_VK_MAX_EXTENSION_PROPERTIES;
    nar_vk_extension_properties *extensions = calloc(count, sizeof(*extensions));
    if (extensions == NULL) return 0;
    nar_vk_result second = enumerate(NULL, &count, extensions);
    int found = 0;
    if (second == NAR_VK_SUCCESS || second == NAR_VK_INCOMPLETE) {
        for (uint32_t i = 0; i < count; ++i) {
            if (strcmp(extensions[i].name, wanted) == 0) { found = 1; break; }
        }
    }
    free(extensions);
    return found;
}

static void *global_proc(const char *name) {
    return nar_vulkan_get_proc_address(name);
}

static void *instance_proc(nar_vk_get_instance_proc_addr_fn gpa, nar_vk_instance instance, const char *name) {
    return gpa == NULL ? NULL : gpa(instance, name);
}

static int has_extension(nar_vk_physical_device device,
                         nar_vk_enumerate_device_extension_properties_fn enumerate,
                         const char *wanted) {
    if (enumerate == NULL || wanted == NULL) return 0;
    uint32_t count = 0;
    if (enumerate(device, NULL, &count, NULL) != NAR_VK_SUCCESS || count == 0) return 0;
    if (count > NAR_VK_MAX_EXTENSION_PROPERTIES) count = NAR_VK_MAX_EXTENSION_PROPERTIES;
    nar_vk_extension_properties *extensions = calloc(count, sizeof(*extensions));
    if (extensions == NULL) return 0;
    int found = 0;
    nar_vk_result enum_result = enumerate(device, NULL, &count, extensions);
    if (enum_result == NAR_VK_SUCCESS || enum_result == NAR_VK_INCOMPLETE) {
        for (uint32_t i = 0; i < count && i < NAR_VK_MAX_EXTENSION_PROPERTIES; ++i) {
            if (strcmp(extensions[i].name, wanted) == 0) { found = 1; break; }
        }
    }
    free(extensions);
    return found;
}

static int create_session(uint32_t mode, uint32_t width, uint32_t height,
                          const nar_vulkan_wsi_window *window,
                          struct nar_vulkan_session **out_session,
                          char *error, size_t error_capacity) {
    if (out_session == NULL) return -1;
    *out_session = NULL;
    if (mode > NAR_VULKAN_MODE_PRESENT || width == 0 || height == 0) {
        copy_text(error, error_capacity, "invalid Vulkan session dimensions or mode");
        return -1;
    }
    if (!nar_vulkan_loader_available()) {
        copy_text(error, error_capacity, "Vulkan loader unavailable");
        return 0;
    }

    nar_vk_get_instance_proc_addr_fn gpa = (nar_vk_get_instance_proc_addr_fn)global_proc("vkGetInstanceProcAddr");
    nar_vk_create_instance_fn create_instance = (nar_vk_create_instance_fn)global_proc("vkCreateInstance");
    nar_vk_enumerate_instance_extension_properties_fn enumerate_instance_extensions =
            (nar_vk_enumerate_instance_extension_properties_fn)global_proc("vkEnumerateInstanceExtensionProperties");
    if (gpa == NULL || create_instance == NULL) {
        copy_text(error, error_capacity, "Vulkan instance entry points unavailable");
        return 0;
    }
    const char *instance_extensions[2] = { "VK_KHR_surface", "VK_KHR_xlib_surface" };
    uint32_t instance_extension_count = 0;
    if (mode == NAR_VULKAN_MODE_PRESENT) {
        if (window == NULL || window->abi_version != NAR_VULKAN_WSI_ABI_VERSION ||
                window->struct_size < sizeof(nar_vulkan_wsi_window)) {
            copy_text(error, error_capacity, "present session requires a valid WSI window descriptor");
            return 0;
        }
        if ((window->width != 0 && window->width != width) ||
                (window->height != 0 && window->height != height)) {
            copy_text(error, error_capacity, "WSI descriptor dimensions disagree with session dimensions");
            return 0;
        }
        if (window->platform != NAR_VULKAN_WSI_PLATFORM_XLIB) {
            copy_text(error, error_capacity, "requested WSI platform has no compiled adapter");
            return 0;
        }
        if (!has_instance_extension(enumerate_instance_extensions, instance_extensions[0]) ||
                !has_instance_extension(enumerate_instance_extensions, instance_extensions[1])) {
            copy_text(error, error_capacity, "required Vulkan surface/Xlib instance extension is unavailable");
            return 0;
        }
        instance_extension_count = 2;
    }
    nar_vk_application_info app;
    memset(&app, 0, sizeof(app));
    app.s_type = NAR_VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.application_name = "Native Accelerator";
    app.application_version = 1;
    app.engine_name = "Native Accelerator Renderer";
    app.engine_version = 1;
    /* Vulkan 1.0 is the common denominator for the BASIC contract. */
    app.api_version = UINT32_C(1) << 22;
    nar_vk_instance_create_info instance_info;
    memset(&instance_info, 0, sizeof(instance_info));
    instance_info.s_type = NAR_VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.application_info = &app;
    instance_info.enabled_extension_count = instance_extension_count;
    instance_info.enabled_extension_names = instance_extension_count == 0 ? NULL : instance_extensions;
    nar_vk_instance instance = NULL;
    nar_vk_result result = create_instance(&instance_info, NULL, &instance);
    if (result != NAR_VK_SUCCESS || instance == NULL) {
        copy_text(error, error_capacity, "vkCreateInstance failed");
        return 0;
    }

    nar_vk_destroy_instance_fn destroy_instance =
        (nar_vk_destroy_instance_fn)instance_proc(gpa, instance, "vkDestroyInstance");
    nar_vk_enumerate_physical_devices_fn enumerate_devices =
        (nar_vk_enumerate_physical_devices_fn)instance_proc(gpa, instance, "vkEnumeratePhysicalDevices");
    nar_vk_get_physical_device_properties_fn get_properties =
        (nar_vk_get_physical_device_properties_fn)instance_proc(gpa, instance, "vkGetPhysicalDeviceProperties");
    nar_vk_get_physical_device_queue_family_properties_fn get_queue_properties =
        (nar_vk_get_physical_device_queue_family_properties_fn)instance_proc(gpa, instance, "vkGetPhysicalDeviceQueueFamilyProperties");
    nar_vk_enumerate_device_extension_properties_fn enumerate_extensions =
        (nar_vk_enumerate_device_extension_properties_fn)instance_proc(gpa, instance, "vkEnumerateDeviceExtensionProperties");
    nar_vk_create_device_fn create_device =
        (nar_vk_create_device_fn)instance_proc(gpa, instance, "vkCreateDevice");
    nar_vk_surface surface = NULL;
    nar_vk_destroy_surface_fn destroy_surface = NULL;
    nar_vk_get_surface_support_fn get_surface_support = NULL;
    if (mode == NAR_VULKAN_MODE_PRESENT) {
        destroy_surface = (nar_vk_destroy_surface_fn)instance_proc(gpa, instance, "vkDestroySurfaceKHR");
        get_surface_support = (nar_vk_get_surface_support_fn)instance_proc(gpa, instance, "vkGetPhysicalDeviceSurfaceSupportKHR");
        if (destroy_surface == NULL || get_surface_support == NULL ||
                nar_vulkan_wsi_create_platform_surface(window->platform, gpa, instance, window,
                        &surface, error, error_capacity) != 0) {
            if (destroy_surface != NULL && surface != NULL) destroy_surface(instance, surface, NULL);
            destroy_instance(instance, NULL);
            if (error == NULL || error[0] == '\0') copy_text(error, error_capacity, "unable to create Vulkan surface");
            return 0;
        }
    }
    if (destroy_instance == NULL || enumerate_devices == NULL || get_properties == NULL
            || get_queue_properties == NULL || create_device == NULL) {
        if (destroy_surface != NULL && surface != NULL) destroy_surface(instance, surface, NULL);
        if (destroy_instance != NULL) destroy_instance(instance, NULL);
        copy_text(error, error_capacity, "Vulkan physical-device entry points unavailable");
        return 0;
    }

    uint32_t device_count = 0;
    if (enumerate_devices(instance, &device_count, NULL) != NAR_VK_SUCCESS || device_count == 0) {
        if (destroy_surface != NULL && surface != NULL) destroy_surface(instance, surface, NULL);
        destroy_instance(instance, NULL);
        copy_text(error, error_capacity, "Vulkan exposes no physical devices");
        return 0;
    }
    if (device_count > 16) device_count = 16;
    nar_vk_physical_device devices[16];
    memset(devices, 0, sizeof(devices));
    if (enumerate_devices(instance, &device_count, devices) != NAR_VK_SUCCESS) {
        if (destroy_surface != NULL && surface != NULL) destroy_surface(instance, surface, NULL);
        destroy_instance(instance, NULL);
        copy_text(error, error_capacity, "vkEnumeratePhysicalDevices failed");
        return 0;
    }

    nar_vk_physical_device selected = NULL;
    uint32_t selected_family = UINT32_MAX;
    uint32_t selected_present_family = UINT32_MAX;
    char selected_name[256] = {0};
    uint64_t optional_features = 0;
    int swapchain_extension_available = 0;
    for (uint32_t d = 0; d < device_count && selected == NULL; ++d) {
        selected_present_family = UINT32_MAX;
        uint32_t family_count = 0;
        get_queue_properties(devices[d], &family_count, NULL);
        if (family_count == 0) continue;
        if (family_count > NAR_VK_MAX_QUEUE_FAMILIES) family_count = NAR_VK_MAX_QUEUE_FAMILIES;
        nar_vk_queue_family_properties families[NAR_VK_MAX_QUEUE_FAMILIES];
        memset(families, 0, sizeof(families));
        get_queue_properties(devices[d], &family_count, families);
        for (uint32_t f = 0; f < family_count; ++f) {
            if ((families[f].queue_flags & NAR_VK_QUEUE_GRAPHICS_BIT) != 0 && families[f].queue_count > 0) {
                nar_vk_bool32 present = 0;
                if (mode == NAR_VULKAN_MODE_PRESENT && get_surface_support != NULL &&
                        get_surface_support(devices[d], f, surface, &present) != NAR_VK_SUCCESS) {
                    present = 0;
                }
                if (mode != NAR_VULKAN_MODE_PRESENT || present) {
                    selected = devices[d];
                    selected_family = f;
                    selected_present_family = present ? f : UINT32_MAX;
                    nar_vk_physical_device_properties properties;
                    memset(&properties, 0, sizeof(properties));
                    get_properties(selected, &properties);
                    memcpy(selected_name, properties.device_name, sizeof(selected_name) - 1);
                    selected_name[sizeof(selected_name) - 1] = '\0';
                    break;
                }
            }
        }
        if (selected == NULL && mode == NAR_VULKAN_MODE_PRESENT) {
            /* A separate present-only queue is allowed when graphics and present
             * are exposed by different families. */
            for (uint32_t f = 0; f < family_count; ++f) {
                nar_vk_bool32 present = 0;
                if (get_surface_support != NULL &&
                        get_surface_support(devices[d], f, surface, &present) == NAR_VK_SUCCESS && present) {
                    selected_present_family = f;
                    break;
                }
            }
            if (selected_present_family != UINT32_MAX) {
                for (uint32_t f = 0; f < family_count; ++f) {
                    if ((families[f].queue_flags & NAR_VK_QUEUE_GRAPHICS_BIT) != 0 && families[f].queue_count > 0) {
                        selected = devices[d];
                        selected_family = f;
                        nar_vk_physical_device_properties properties;
                        memset(&properties, 0, sizeof(properties));
                        get_properties(selected, &properties);
                        memcpy(selected_name, properties.device_name, sizeof(selected_name) - 1);
                        selected_name[sizeof(selected_name) - 1] = '\0';
                        break;
                    }
                }
            }
        }
        if (selected != NULL) {
            swapchain_extension_available = has_extension(selected, enumerate_extensions, "VK_KHR_swapchain");
            if (mode == NAR_VULKAN_MODE_PRESENT && !swapchain_extension_available) {
                selected = NULL;
                selected_family = UINT32_MAX;
                selected_present_family = UINT32_MAX;
                continue;
            }
            if (has_extension(selected, enumerate_extensions, "VK_KHR_dynamic_rendering")) optional_features |= NAR_VULKAN_FEATURE_DYNAMIC_RENDERING;
            if (has_extension(selected, enumerate_extensions, "VK_KHR_synchronization2")) optional_features |= NAR_VULKAN_FEATURE_SYNCHRONIZATION2;
            if (has_extension(selected, enumerate_extensions, "VK_KHR_push_descriptor")) optional_features |= NAR_VULKAN_FEATURE_PUSH_DESCRIPTOR;
            if (has_extension(selected, enumerate_extensions, "VK_EXT_vertex_attribute_divisor")) optional_features |= NAR_VULKAN_FEATURE_VERTEX_DIVISOR;
        }
    }
    if (selected == NULL) {
        if (destroy_surface != NULL && surface != NULL) destroy_surface(instance, surface, NULL);
        destroy_instance(instance, NULL);
        copy_text(error, error_capacity, mode == NAR_VULKAN_MODE_PRESENT
                ? "no physical device exposes graphics, present, and VK_KHR_swapchain support"
                : "no physical device exposes a graphics queue");
        return 0;
    }

    float priority = 1.0f;
    nar_vk_device_queue_create_info queue_info;
    memset(&queue_info, 0, sizeof(queue_info));
    queue_info.s_type = NAR_VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queue_info.queue_family_index = selected_family;
    queue_info.queue_count = 1;
    queue_info.queue_priorities = &priority;
    const char *device_extensions[1] = { "VK_KHR_swapchain" };
    nar_vk_device_queue_create_info queue_infos[2];
    memset(queue_infos, 0, sizeof(queue_infos));
    queue_infos[0] = queue_info;
    uint32_t queue_info_count = 1;
    if (mode == NAR_VULKAN_MODE_PRESENT && selected_present_family != selected_family) {
        queue_infos[1] = queue_info;
        queue_infos[1].queue_family_index = selected_present_family;
        queue_info_count = 2;
    }
    nar_vk_device_create_info device_info;
    memset(&device_info, 0, sizeof(device_info));
    device_info.s_type = NAR_VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    device_info.queue_create_info_count = queue_info_count;
    device_info.queue_create_infos = queue_infos;
    device_info.enabled_extension_count = mode == NAR_VULKAN_MODE_PRESENT ? 1u : 0u;
    device_info.enabled_extension_names = mode == NAR_VULKAN_MODE_PRESENT ? device_extensions : NULL;
    nar_vk_device device = NULL;
    if (create_device(selected, &device_info, NULL, &device) != NAR_VK_SUCCESS || device == NULL) {
        if (destroy_surface != NULL && surface != NULL) destroy_surface(instance, surface, NULL);
        destroy_instance(instance, NULL);
        copy_text(error, error_capacity, "vkCreateDevice failed for the requested Vulkan contract");
        return 0;
    }

    nar_vk_get_device_proc_addr_fn gdpa = (nar_vk_get_device_proc_addr_fn)instance_proc(gpa, instance, "vkGetDeviceProcAddr");
    nar_vk_get_device_queue_fn get_queue = gdpa == NULL ? NULL : (nar_vk_get_device_queue_fn)gdpa(device, "vkGetDeviceQueue");
    nar_vk_create_command_pool_fn create_pool = gdpa == NULL ? NULL : (nar_vk_create_command_pool_fn)gdpa(device, "vkCreateCommandPool");
    nar_vk_destroy_device_fn destroy_device = gdpa == NULL ? NULL : (nar_vk_destroy_device_fn)gdpa(device, "vkDestroyDevice");
    nar_vk_device_wait_idle_fn device_wait_idle = gdpa == NULL ? NULL : (nar_vk_device_wait_idle_fn)gdpa(device, "vkDeviceWaitIdle");
    nar_vk_destroy_command_pool_fn destroy_pool = gdpa == NULL ? NULL : (nar_vk_destroy_command_pool_fn)gdpa(device, "vkDestroyCommandPool");
    nar_vk_create_swapchain_fn create_swapchain = gdpa == NULL ? NULL : (nar_vk_create_swapchain_fn)gdpa(device, "vkCreateSwapchainKHR");
    nar_vk_destroy_swapchain_fn destroy_swapchain = gdpa == NULL ? NULL : (nar_vk_destroy_swapchain_fn)gdpa(device, "vkDestroySwapchainKHR");
    nar_vk_get_swapchain_images_fn get_swapchain_images = gdpa == NULL ? NULL : (nar_vk_get_swapchain_images_fn)gdpa(device, "vkGetSwapchainImagesKHR");
    nar_vk_acquire_next_image_fn acquire_next_image = gdpa == NULL ? NULL : (nar_vk_acquire_next_image_fn)gdpa(device, "vkAcquireNextImageKHR");
    nar_vk_queue_present_fn queue_present = gdpa == NULL ? NULL : (nar_vk_queue_present_fn)gdpa(device, "vkQueuePresentKHR");
    if (get_queue == NULL || create_pool == NULL || destroy_device == NULL || destroy_pool == NULL ||
            (mode == NAR_VULKAN_MODE_PRESENT &&
             (create_swapchain == NULL || destroy_swapchain == NULL || get_swapchain_images == NULL ||
              acquire_next_image == NULL || queue_present == NULL))) {
        if (destroy_device != NULL) destroy_device(device, NULL);
        else {
            void (*fallback_destroy)(nar_vk_device, const void *) =
                (void (*)(nar_vk_device, const void *))instance_proc(gpa, instance, "vkDestroyDevice");
            if (fallback_destroy != NULL) fallback_destroy(device, NULL);
        }
        if (destroy_surface != NULL && surface != NULL) destroy_surface(instance, surface, NULL);
        destroy_instance(instance, NULL);
        copy_text(error, error_capacity, "Vulkan device entry points unavailable");
        return 0;
    }
    nar_vk_command_pool_create_info pool_info;
    memset(&pool_info, 0, sizeof(pool_info));
    pool_info.s_type = NAR_VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool_info.flags = NAR_VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool_info.queue_family_index = selected_family;
    nar_vk_command_pool pool = NULL;
    if (create_pool(device, &pool_info, NULL, &pool) != NAR_VK_SUCCESS || pool == NULL) {
        destroy_device(device, NULL);
        if (destroy_surface != NULL && surface != NULL) destroy_surface(instance, surface, NULL);
        destroy_instance(instance, NULL);
        copy_text(error, error_capacity, "vkCreateCommandPool failed");
        return 0;
    }
    struct nar_vulkan_session *session = calloc(1, sizeof(*session));
    if (session == NULL) {
        destroy_pool(device, pool, NULL);
        destroy_device(device, NULL);
        if (destroy_surface != NULL && surface != NULL) destroy_surface(instance, surface, NULL);
        destroy_instance(instance, NULL);
        copy_text(error, error_capacity, "out of memory creating Vulkan session");
        return 0;
    }
    session->mode = mode;
    session->width = width;
    session->height = height;
    session->tier = mode == NAR_VULKAN_MODE_PRESENT ? NAR_VULKAN_TIER_PRESENT : NAR_VULKAN_TIER_BASIC;
    session->features = NAR_VULKAN_FEATURE_CLASSIC_RENDER_PASS | NAR_VULKAN_FEATURE_CPU_CULL | optional_features;
    if (mode == NAR_VULKAN_MODE_PRESENT) session->features |= NAR_VULKAN_FEATURE_SWAPCHAIN;
    session->graphics_queue_family = selected_family;
    session->present_queue_family = mode == NAR_VULKAN_MODE_PRESENT ? selected_present_family : UINT32_MAX;
    session->instance = instance;
    session->physical_device = selected;
    session->device = device;
    session->command_pool = pool;
    session->destroy_instance = destroy_instance;
    session->destroy_device = destroy_device;
    session->device_wait_idle = device_wait_idle;
    session->destroy_command_pool = destroy_pool;
    session->destroy_surface = destroy_surface;
    session->gipa = gpa;
    session->gdpa = gdpa;
    session->surface = surface;
    session->create_swapchain = create_swapchain;
    session->destroy_swapchain = destroy_swapchain;
    session->get_swapchain_images = get_swapchain_images;
    session->acquire_next_image = acquire_next_image;
    session->queue_present = queue_present;
    get_queue(device, selected_family, 0, &session->graphics_queue);
    if (mode == NAR_VULKAN_MODE_PRESENT)
        get_queue(device, selected_present_family, 0, &session->present_queue);
    copy_text(session->device_name, sizeof(session->device_name), selected_name[0] == '\0' ? "Vulkan physical device" : selected_name);
    copy_text(session->error, sizeof(session->error), mode == NAR_VULKAN_MODE_PRESENT
            ? "present-capable Vulkan session ready (surface and VK_KHR_swapchain verified)"
            : "offscreen BASIC Vulkan session ready");
    *out_session = session;
    return 1;
}

int32_t nar_vulkan_query_capabilities(uint32_t mode, nar_vulkan_capability_report *out_report) {
    if (out_report == NULL || out_report->struct_size < sizeof(*out_report)) return -1;
    memset(out_report, 0, sizeof(*out_report));
    out_report->struct_size = sizeof(*out_report);
    out_report->abi_version = NAR_VULKAN_ABI_VERSION;
    out_report->api_version = nar_vulkan_loader_api_version();
    out_report->physical_device_count = nar_vulkan_physical_device_count();
    struct nar_vulkan_session *session = NULL;
    char error[512] = {0};
    int status = create_session(mode, 1, 1, NULL, &session, error, sizeof(error));
    if (status <= 0 || session == NULL) {
        out_report->tier = NAR_VULKAN_TIER_UNAVAILABLE;
        copy_text(out_report->diagnostic, sizeof(out_report->diagnostic), error[0] ? error : "no usable Vulkan graphics device");
        return 0;
    }
    out_report->tier = session->tier;
    out_report->features = session->features;
    out_report->graphics_queue_available = 1;
    out_report->present_queue_available = session->present_queue_family != UINT32_MAX;
    copy_text(out_report->device_name, sizeof(out_report->device_name), session->device_name);
    copy_text(out_report->diagnostic, sizeof(out_report->diagnostic), session->error);
    nar_vulkan_session_destroy(session);
    return 0;
}

nar_vulkan_session *nar_vulkan_session_create(uint32_t mode, uint32_t width, uint32_t height) {
    struct nar_vulkan_session *session = NULL;
    char error[512] = {0};
    if (mode == NAR_VULKAN_MODE_PRESENT) return NULL;
    if (create_session(mode, width, height, NULL, &session, error, sizeof(error)) <= 0) return NULL;
    return session;
}

nar_vulkan_session *nar_vulkan_session_create_present(
        const nar_vulkan_wsi_window *window, uint32_t width, uint32_t height) {
    struct nar_vulkan_session *session = NULL;
    char error[512] = {0};
    if (create_session(NAR_VULKAN_MODE_PRESENT, width, height, window,
            &session, error, sizeof(error)) <= 0) return NULL;
    return session;
}

void nar_vulkan_session_destroy(nar_vulkan_session *session) {
    if (session == NULL) return;
    if (session->device_wait_idle != NULL && session->device != NULL)
        (void)session->device_wait_idle(session->device);
    while (session->swapchains != NULL)
        destroy_swapchain_resources(session, session->swapchains, 1);
    if (session->destroy_command_pool != NULL && session->device != NULL && session->command_pool != NULL)
        session->destroy_command_pool(session->device, session->command_pool, NULL);
    if (session->destroy_device != NULL && session->device != NULL) session->destroy_device(session->device, NULL);
    if (session->destroy_surface != NULL && session->instance != NULL && session->surface != NULL)
        session->destroy_surface(session->instance, session->surface, NULL);
    if (session->destroy_instance != NULL && session->instance != NULL) session->destroy_instance(session->instance, NULL);
    free(session);
}

uint32_t nar_vulkan_session_tier(const nar_vulkan_session *session) {
    return session == NULL ? NAR_VULKAN_TIER_UNAVAILABLE : session->tier;
}

uint64_t nar_vulkan_session_features(const nar_vulkan_session *session) {
    return session == NULL ? 0 : session->features;
}

uint64_t nar_vulkan_session_last_error(const nar_vulkan_session *session, char *dst, uint64_t capacity) {
    const char *error = session == NULL ? "no Vulkan session" : session->error;
    uint64_t length = (uint64_t)strlen(error);
    if (dst != NULL && capacity > 0) {
        uint64_t copy = length < capacity - 1 ? length : capacity - 1;
        memcpy(dst, error, (size_t)copy);
        dst[copy] = '\0';
    }
    return length;
}

static void *dp(nar_vk_get_device_proc_addr_fn gdpa, nar_vk_device dev, const char *name);

static int wsi_report_init(nar_vulkan_wsi_report *report) {
    if (report == NULL || report->struct_size < sizeof(*report)) return -1;
    memset(report, 0, sizeof(*report));
    report->struct_size = sizeof(*report);
    report->abi_version = NAR_VULKAN_WSI_ABI_VERSION;
    return 0;
}

int32_t nar_vulkan_wsi_supported(uint32_t platform, nar_vulkan_wsi_report *out_report) {
    if (wsi_report_init(out_report) != 0) return -1;
    out_report->platform = platform;
    if (platform != NAR_VULKAN_WSI_PLATFORM_XLIB) {
        out_report->status = NAR_VULKAN_WSI_STATUS_UNSUPPORTED;
        copy_text(out_report->diagnostic, sizeof(out_report->diagnostic),
                "this companion currently provides only the isolated Xlib WSI adapter");
        return 0;
    }
    nar_vk_enumerate_instance_extension_properties_fn enumerate =
            (nar_vk_enumerate_instance_extension_properties_fn)global_proc(
                    "vkEnumerateInstanceExtensionProperties");
    if (!nar_vulkan_loader_available() || enumerate == NULL) {
        out_report->status = NAR_VULKAN_WSI_STATUS_UNAVAILABLE;
        copy_text(out_report->diagnostic, sizeof(out_report->diagnostic),
                "Vulkan loader or instance-extension enumeration is unavailable");
        return 0;
    }
    if (!has_instance_extension(enumerate, "VK_KHR_surface") ||
            !has_instance_extension(enumerate, "VK_KHR_xlib_surface")) {
        out_report->status = NAR_VULKAN_WSI_STATUS_UNSUPPORTED;
        copy_text(out_report->diagnostic, sizeof(out_report->diagnostic),
                "VK_KHR_surface and VK_KHR_xlib_surface are both required");
        return 0;
    }
    out_report->supported = 1;
    out_report->status = NAR_VULKAN_WSI_STATUS_OK;
    copy_text(out_report->diagnostic, sizeof(out_report->diagnostic),
            "Xlib WSI instance extensions are available; a live window is still required");
    return 0;
}

int32_t nar_vulkan_wsi_report_session(const nar_vulkan_session *session,
                                      nar_vulkan_wsi_report *out_report) {
    if (wsi_report_init(out_report) != 0) return -1;
    if (session == NULL || session->mode != NAR_VULKAN_MODE_PRESENT || session->surface == NULL) {
        out_report->status = NAR_VULKAN_WSI_STATUS_UNAVAILABLE;
        copy_text(out_report->diagnostic, sizeof(out_report->diagnostic),
                "session has no verified presentation surface");
        return 0;
    }
    out_report->supported = 1;
    out_report->status = NAR_VULKAN_WSI_STATUS_OK;
    out_report->graphics_queue_family = session->graphics_queue_family;
    out_report->present_queue_family = session->present_queue_family;
    out_report->width = session->width;
    out_report->height = session->height;
    copy_text(out_report->diagnostic, sizeof(out_report->diagnostic), session->error);
    return 0;
}

static void destroy_swapchain_resources(nar_vulkan_session *session,
                                         nar_vulkan_swapchain *swapchain,
                                         int free_struct);

static int32_t wsi_status_from_vk(nar_vk_result result) {
    if (result == NAR_VK_SUCCESS) return (int32_t)NAR_VULKAN_WSI_STATUS_OK;
    if (result == NAR_VK_SUBOPTIMAL) return (int32_t)NAR_VULKAN_WSI_STATUS_SUBOPTIMAL;
    if (result == NAR_VK_ERROR_OUT_OF_DATE) return (int32_t)NAR_VULKAN_WSI_STATUS_OUT_OF_DATE;
    if (result == NAR_VK_ERROR_SURFACE_LOST) return (int32_t)NAR_VULKAN_WSI_STATUS_SURFACE_LOST;
    if (result == -4) return (int32_t)NAR_VULKAN_WSI_STATUS_DEVICE_LOST;
    if (result == 1) return (int32_t)NAR_VULKAN_WSI_STATUS_NOT_READY;
    if (result == 2) return (int32_t)NAR_VULKAN_WSI_STATUS_TIMEOUT;
    return (int32_t)NAR_VULKAN_WSI_STATUS_ERROR;
}

nar_vulkan_swapchain *nar_vulkan_swapchain_create(
        nar_vulkan_session *session, uint32_t width, uint32_t height) {
    if (session == NULL || session->mode != NAR_VULKAN_MODE_PRESENT ||
            session->surface == NULL || width == 0 || height == 0 ||
            session->create_swapchain == NULL) return NULL;
    nar_vk_get_surface_capabilities_fn get_caps =
            (nar_vk_get_surface_capabilities_fn)instance_proc(session->gipa, session->instance,
                    "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
    nar_vk_get_surface_formats_fn get_formats =
            (nar_vk_get_surface_formats_fn)instance_proc(session->gipa, session->instance,
                    "vkGetPhysicalDeviceSurfaceFormatsKHR");
    nar_vk_get_surface_present_modes_fn get_modes =
            (nar_vk_get_surface_present_modes_fn)instance_proc(session->gipa, session->instance,
                    "vkGetPhysicalDeviceSurfacePresentModesKHR");
    if (get_caps == NULL || get_formats == NULL || get_modes == NULL) {
        copy_text(session->error, sizeof(session->error), "WSI: surface query entry points unavailable");
        return NULL;
    }
    nar_vk_surface_capabilities caps;
    memset(&caps, 0, sizeof(caps));
    nar_vk_result result = get_caps(session->physical_device, session->surface, &caps);
    if (result != NAR_VK_SUCCESS) {
        copy_text(session->error, sizeof(session->error), "WSI: vkGetPhysicalDeviceSurfaceCapabilitiesKHR failed");
        return NULL;
    }
    uint32_t format_count = 16;
    nar_vk_surface_format formats[16];
    memset(formats, 0, sizeof(formats));
    result = get_formats(session->physical_device, session->surface, &format_count, formats);
    if ((result != NAR_VK_SUCCESS && result != NAR_VK_INCOMPLETE) || format_count == 0) {
        copy_text(session->error, sizeof(session->error), "WSI: no supported surface formats");
        return NULL;
    }
    if (format_count > 16) format_count = 16;
    uint32_t chosen_format = formats[0].format;
    uint32_t chosen_color_space = formats[0].color_space;
    for (uint32_t i = 0; i < format_count; ++i) {
        if (formats[i].format == NAR_VK_FORMAT_B8G8R8A8_SRGB &&
                formats[i].color_space == NAR_VK_COLOR_SPACE_SRGB_NONLINEAR) {
            chosen_format = formats[i].format; chosen_color_space = formats[i].color_space; break;
        }
    }
    uint32_t mode_count = 16;
    uint32_t modes[16];
    memset(modes, 0, sizeof(modes));
    result = get_modes(session->physical_device, session->surface, &mode_count, modes);
    if ((result != NAR_VK_SUCCESS && result != NAR_VK_INCOMPLETE) || mode_count == 0) {
        copy_text(session->error, sizeof(session->error), "WSI: no supported present modes");
        return NULL;
    }
    uint32_t present_mode = NAR_VK_PRESENT_MODE_FIFO;
    int fifo = 0;
    for (uint32_t i = 0; i < mode_count && i < 16; ++i) if (modes[i] == present_mode) fifo = 1;
    if (!fifo) present_mode = modes[0];
    uint32_t actual_width = caps.current_extent_width == UINT32_MAX ? width : caps.current_extent_width;
    uint32_t actual_height = caps.current_extent_height == UINT32_MAX ? height : caps.current_extent_height;
    if (caps.current_extent_width == UINT32_MAX) {
        if (actual_width < caps.min_image_extent_width) actual_width = caps.min_image_extent_width;
        if (actual_width > caps.max_image_extent_width) actual_width = caps.max_image_extent_width;
        if (actual_height < caps.min_image_extent_height) actual_height = caps.min_image_extent_height;
        if (actual_height > caps.max_image_extent_height) actual_height = caps.max_image_extent_height;
    }
    uint32_t image_count = caps.min_image_count + 1;
    if (caps.max_image_count != 0 && image_count > caps.max_image_count) image_count = caps.max_image_count;
    if (image_count > 16) image_count = 16;
    uint32_t usage = caps.supported_usage_flags & NAR_VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
            ? NAR_VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
            : (caps.supported_usage_flags & NAR_VK_IMAGE_USAGE_TRANSFER_DST_BIT
                    ? NAR_VK_IMAGE_USAGE_TRANSFER_DST_BIT : 0);
    if (usage == 0) {
        copy_text(session->error, sizeof(session->error), "WSI: surface cannot be used as a color target");
        return NULL;
    }
    uint32_t queue_families[2] = { session->graphics_queue_family, session->present_queue_family };
    nar_vk_swapchain_create_info info;
    memset(&info, 0, sizeof(info));
    info.s_type = NAR_VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO;
    info.surface = session->surface;
    info.min_image_count = image_count;
    info.image_format = chosen_format;
    info.image_color_space = chosen_color_space;
    info.image_extent_width = actual_width;
    info.image_extent_height = actual_height;
    info.image_array_layers = 1;
    info.image_usage = usage;
    info.image_sharing_mode = session->graphics_queue_family == session->present_queue_family
            ? NAR_VK_SHARING_MODE_EXCLUSIVE : NAR_VK_SHARING_MODE_CONCURRENT;
    info.queue_family_index_count = info.image_sharing_mode == NAR_VK_SHARING_MODE_CONCURRENT ? 2 : 0;
    info.queue_family_indices = info.queue_family_index_count == 0 ? NULL : queue_families;
    info.pre_transform = caps.current_transform;
    if (caps.supported_composite_alpha & NAR_VK_COMPOSITE_ALPHA_OPAQUE_BIT)
        info.composite_alpha = NAR_VK_COMPOSITE_ALPHA_OPAQUE_BIT;
    else if (caps.supported_composite_alpha & NAR_VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT)
        info.composite_alpha = NAR_VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT;
    else if (caps.supported_composite_alpha & NAR_VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT)
        info.composite_alpha = NAR_VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT;
    else if (caps.supported_composite_alpha & NAR_VK_COMPOSITE_ALPHA_INHERIT_BIT)
        info.composite_alpha = NAR_VK_COMPOSITE_ALPHA_INHERIT_BIT;
    else {
        copy_text(session->error, sizeof(session->error), "WSI: no supported composite-alpha mode");
        return NULL;
    }
    info.present_mode = present_mode;
    info.clipped = 1;
    nar_vk_swapchain handle = NULL;
    result = session->create_swapchain(session->device, &info, NULL, &handle);
    if (result != NAR_VK_SUCCESS || handle == NULL) {
        copy_text(session->error, sizeof(session->error), "WSI: vkCreateSwapchainKHR failed");
        return NULL;
    }
    struct nar_vulkan_swapchain *swapchain = calloc(1, sizeof(*swapchain));
    if (swapchain == NULL) {
        session->destroy_swapchain(session->device, handle, NULL);
        copy_text(session->error, sizeof(session->error), "WSI: out of memory for swapchain");
        return NULL;
    }
    swapchain->handle = handle;
    swapchain->width = actual_width; swapchain->height = actual_height;
    swapchain->format = chosen_format; swapchain->color_space = chosen_color_space;
    swapchain->present_mode = present_mode;
    swapchain->image_count = image_count;
    uint32_t actual_count = 16;
    result = session->get_swapchain_images(session->device, handle, &actual_count, swapchain->images);
    if (result != NAR_VK_SUCCESS && result != NAR_VK_INCOMPLETE) {
        session->destroy_swapchain(session->device, handle, NULL); free(swapchain);
        copy_text(session->error, sizeof(session->error), "WSI: vkGetSwapchainImagesKHR failed");
        return NULL;
    }
    swapchain->image_count = actual_count > NAR_VK_MAX_SWAPCHAIN_IMAGES ? NAR_VK_MAX_SWAPCHAIN_IMAGES : actual_count;
    if (swapchain->image_count == 0) {
        session->destroy_swapchain(session->device, handle, NULL);
        free(swapchain);
        copy_text(session->error, sizeof(session->error), "WSI: swapchain returned no images");
        return NULL;
    }
    nar_vk_create_image_view_fn create_view =
            (nar_vk_create_image_view_fn)dp(session->gdpa, session->device, "vkCreateImageView");
    nar_vk_destroy_image_view_fn destroy_view =
            (nar_vk_destroy_image_view_fn)dp(session->gdpa, session->device, "vkDestroyImageView");
    nar_vk_create_render_pass_fn create_render_pass =
            (nar_vk_create_render_pass_fn)dp(session->gdpa, session->device, "vkCreateRenderPass");
    nar_vk_destroy_render_pass_fn destroy_render_pass =
            (nar_vk_destroy_render_pass_fn)dp(session->gdpa, session->device, "vkDestroyRenderPass");
    nar_vk_create_framebuffer_fn create_framebuffer =
            (nar_vk_create_framebuffer_fn)dp(session->gdpa, session->device, "vkCreateFramebuffer");
    nar_vk_destroy_framebuffer_fn destroy_framebuffer =
            (nar_vk_destroy_framebuffer_fn)dp(session->gdpa, session->device, "vkDestroyFramebuffer");
    nar_vk_allocate_command_buffers_fn allocate_command_buffers =
            (nar_vk_allocate_command_buffers_fn)dp(session->gdpa, session->device, "vkAllocateCommandBuffers");
    nar_vk_create_fence_fn create_fence =
            (nar_vk_create_fence_fn)dp(session->gdpa, session->device, "vkCreateFence");
    nar_vk_create_semaphore_fn create_semaphore =
            (nar_vk_create_semaphore_fn)dp(session->gdpa, session->device, "vkCreateSemaphore");
    if (create_view == NULL || destroy_view == NULL || create_render_pass == NULL ||
            destroy_render_pass == NULL || create_framebuffer == NULL || destroy_framebuffer == NULL ||
            allocate_command_buffers == NULL || create_fence == NULL || create_semaphore == NULL) {
        session->destroy_swapchain(session->device, handle, NULL); free(swapchain);
        copy_text(session->error, sizeof(session->error), "WSI: frame resource entry points unavailable");
        return NULL;
    }

    nar_vk_image_view_create_info view_info;
    memset(&view_info, 0, sizeof(view_info));
    view_info.s_type = NAR_VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    view_info.view_type = NAR_VK_IMAGE_VIEW_TYPE_2D;
    view_info.format = chosen_format;
    view_info.subresource_range.aspect_mask = NAR_VK_IMAGE_ASPECT_COLOR_BIT;
    view_info.subresource_range.level_count = 1;
    view_info.subresource_range.layer_count = 1;
    for (uint32_t i = 0; i < swapchain->image_count; ++i) {
        view_info.image = swapchain->images[i];
        if (create_view(session->device, &view_info, NULL, &swapchain->image_views[i]) != NAR_VK_SUCCESS ||
                swapchain->image_views[i] == NULL) {
            for (uint32_t j = 0; j < i; ++j) destroy_view(session->device, swapchain->image_views[j], NULL);
            session->destroy_swapchain(session->device, handle, NULL); free(swapchain);
            copy_text(session->error, sizeof(session->error), "WSI: vkCreateImageView failed");
            return NULL;
        }
    }
    swapchain->image_view_count = swapchain->image_count;

    nar_vk_attachment_description attachment;
    memset(&attachment, 0, sizeof(attachment));
    attachment.format = chosen_format;
    attachment.samples = NAR_VK_SAMPLE_COUNT_1_BIT;
    attachment.load_op = NAR_VK_ATTACHMENT_LOAD_OP_CLEAR;
    attachment.store_op = NAR_VK_ATTACHMENT_STORE_OP_STORE;
    attachment.stencil_load_op = 2;
    attachment.stencil_store_op = 1;
    attachment.initial_layout = NAR_VK_IMAGE_LAYOUT_UNDEFINED;
    attachment.final_layout = NAR_VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    nar_vk_attachment_reference color_ref = { 0, NAR_VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL };
    nar_vk_subpass_description subpass;
    memset(&subpass, 0, sizeof(subpass));
    subpass.pipeline_bind_point = NAR_VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.color_attachment_count = 1;
    subpass.color_attachments = &color_ref;
    nar_vk_render_pass_create_info render_pass_info;
    memset(&render_pass_info, 0, sizeof(render_pass_info));
    render_pass_info.s_type = NAR_VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    render_pass_info.attachment_count = 1;
    render_pass_info.attachments = &attachment;
    render_pass_info.subpass_count = 1;
    render_pass_info.subpasses = &subpass;
    if (create_render_pass(session->device, &render_pass_info, NULL, &swapchain->render_pass) != NAR_VK_SUCCESS ||
            swapchain->render_pass == NULL) {
        for (uint32_t i = 0; i < swapchain->image_count; ++i) destroy_view(session->device, swapchain->image_views[i], NULL);
        session->destroy_swapchain(session->device, handle, NULL); free(swapchain);
        copy_text(session->error, sizeof(session->error), "WSI: swapchain render pass creation failed");
        return NULL;
    }
    for (uint32_t i = 0; i < swapchain->image_count; ++i) {
        nar_vk_framebuffer_create_info framebuffer_info;
        memset(&framebuffer_info, 0, sizeof(framebuffer_info));
        framebuffer_info.s_type = NAR_VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        framebuffer_info.render_pass = swapchain->render_pass;
        framebuffer_info.attachment_count = 1;
        framebuffer_info.attachments = &swapchain->image_views[i];
        framebuffer_info.width = actual_width;
        framebuffer_info.height = actual_height;
        framebuffer_info.layers = 1;
        if (create_framebuffer(session->device, &framebuffer_info, NULL, &swapchain->framebuffers[i]) != NAR_VK_SUCCESS ||
                swapchain->framebuffers[i] == NULL) {
            for (uint32_t j = 0; j < i; ++j) destroy_framebuffer(session->device, swapchain->framebuffers[j], NULL);
            destroy_render_pass(session->device, swapchain->render_pass, NULL);
            for (uint32_t j = 0; j < swapchain->image_count; ++j) destroy_view(session->device, swapchain->image_views[j], NULL);
            session->destroy_swapchain(session->device, handle, NULL); free(swapchain);
            copy_text(session->error, sizeof(session->error), "WSI: swapchain framebuffer creation failed");
            return NULL;
        }
    }

    nar_vk_command_buffer_allocate_info command_info;
    memset(&command_info, 0, sizeof(command_info));
    command_info.s_type = NAR_VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    command_info.command_pool = session->command_pool;
    command_info.level = NAR_VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    command_info.command_buffer_count = NAR_VK_MAX_FRAMES_IN_FLIGHT;
    nar_vk_command_buffer command_buffers[NAR_VK_MAX_FRAMES_IN_FLIGHT] = { NULL, NULL };
    if (allocate_command_buffers(session->device, &command_info, command_buffers) != NAR_VK_SUCCESS) {
        for (uint32_t i = 0; i < swapchain->image_count; ++i) destroy_framebuffer(session->device, swapchain->framebuffers[i], NULL);
        destroy_render_pass(session->device, swapchain->render_pass, NULL);
        for (uint32_t i = 0; i < swapchain->image_count; ++i) destroy_view(session->device, swapchain->image_views[i], NULL);
        session->destroy_swapchain(session->device, handle, NULL); free(swapchain);
        copy_text(session->error, sizeof(session->error), "WSI: swapchain command buffer allocation failed");
        return NULL;
    }
    nar_vk_fence_create_info fence_info;
    memset(&fence_info, 0, sizeof(fence_info));
    fence_info.s_type = NAR_VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fence_info.flags = NAR_VK_FENCE_CREATE_SIGNALED_BIT;
    nar_vk_semaphore_create_info semaphore_info;
    memset(&semaphore_info, 0, sizeof(semaphore_info));
    semaphore_info.s_type = NAR_VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    for (uint32_t i = 0; i < NAR_VK_MAX_FRAMES_IN_FLIGHT; ++i) {
        swapchain->frames[i].command_buffer = command_buffers[i];
        if (create_fence(session->device, &fence_info, NULL, &swapchain->frames[i].fence) != NAR_VK_SUCCESS ||
                create_semaphore(session->device, &semaphore_info, NULL, &swapchain->frames[i].image_available) != NAR_VK_SUCCESS ||
                create_semaphore(session->device, &semaphore_info, NULL, &swapchain->frames[i].render_finished) != NAR_VK_SUCCESS) {
            copy_text(session->error, sizeof(session->error), "WSI: frame synchronization creation failed");
            for (uint32_t j = 0; j <= i; ++j) {
                nar_vk_destroy_fence_fn destroy_fence =
                        (nar_vk_destroy_fence_fn)dp(session->gdpa, session->device, "vkDestroyFence");
                nar_vk_destroy_semaphore_fn destroy_semaphore =
                        (nar_vk_destroy_semaphore_fn)dp(session->gdpa, session->device, "vkDestroySemaphore");
                if (destroy_fence != NULL && swapchain->frames[j].fence != NULL)
                    destroy_fence(session->device, swapchain->frames[j].fence, NULL);
                if (destroy_semaphore != NULL && swapchain->frames[j].image_available != NULL)
                    destroy_semaphore(session->device, swapchain->frames[j].image_available, NULL);
                if (destroy_semaphore != NULL && swapchain->frames[j].render_finished != NULL)
                    destroy_semaphore(session->device, swapchain->frames[j].render_finished, NULL);
            }
            nar_vk_free_command_buffers_fn free_command_buffers =
                    (nar_vk_free_command_buffers_fn)dp(session->gdpa, session->device, "vkFreeCommandBuffers");
            if (free_command_buffers != NULL)
                free_command_buffers(session->device, session->command_pool,
                        NAR_VK_MAX_FRAMES_IN_FLIGHT, command_buffers);
            for (uint32_t j = 0; j < swapchain->image_count; ++j) destroy_framebuffer(session->device, swapchain->framebuffers[j], NULL);
            destroy_render_pass(session->device, swapchain->render_pass, NULL);
            for (uint32_t j = 0; j < swapchain->image_count; ++j) destroy_view(session->device, swapchain->image_views[j], NULL);
            session->destroy_swapchain(session->device, handle, NULL);
            free(swapchain);
            return NULL;
        }
    }
    swapchain->next = session->swapchains;
    session->swapchains = swapchain;
    return swapchain;
}

static void destroy_swapchain_resources(nar_vulkan_session *session,
                                         nar_vulkan_swapchain *swapchain,
                                         int free_struct) {
    if (swapchain == NULL) return;
    if (session == NULL || session->device == NULL) {
        if (free_struct) free(swapchain);
        return;
    }
    if (free_struct) {
        struct nar_vulkan_swapchain **cursor = &session->swapchains;
        while (*cursor != NULL && *cursor != swapchain) cursor = &(*cursor)->next;
        if (*cursor == swapchain) *cursor = swapchain->next;
    }
    if (session->device_wait_idle != NULL) (void)session->device_wait_idle(session->device);
    nar_vk_destroy_fence_fn destroy_fence =
            (nar_vk_destroy_fence_fn)dp(session->gdpa, session->device, "vkDestroyFence");
    nar_vk_destroy_semaphore_fn destroy_semaphore =
            (nar_vk_destroy_semaphore_fn)dp(session->gdpa, session->device, "vkDestroySemaphore");
    nar_vk_destroy_framebuffer_fn destroy_framebuffer =
            (nar_vk_destroy_framebuffer_fn)dp(session->gdpa, session->device, "vkDestroyFramebuffer");
    nar_vk_destroy_render_pass_fn destroy_render_pass =
            (nar_vk_destroy_render_pass_fn)dp(session->gdpa, session->device, "vkDestroyRenderPass");
    nar_vk_destroy_image_view_fn destroy_view =
            (nar_vk_destroy_image_view_fn)dp(session->gdpa, session->device, "vkDestroyImageView");
    nar_vk_free_command_buffers_fn free_command_buffers =
            (nar_vk_free_command_buffers_fn)dp(session->gdpa, session->device, "vkFreeCommandBuffers");
    for (uint32_t i = 0; i < NAR_VK_MAX_FRAMES_IN_FLIGHT; ++i) {
        if (destroy_fence != NULL && swapchain->frames[i].fence != NULL)
            destroy_fence(session->device, swapchain->frames[i].fence, NULL);
        if (destroy_semaphore != NULL && swapchain->frames[i].image_available != NULL)
            destroy_semaphore(session->device, swapchain->frames[i].image_available, NULL);
        if (destroy_semaphore != NULL && swapchain->frames[i].render_finished != NULL)
            destroy_semaphore(session->device, swapchain->frames[i].render_finished, NULL);
    }
    if (free_command_buffers != NULL) {
        nar_vk_command_buffer buffers[NAR_VK_MAX_FRAMES_IN_FLIGHT];
        uint32_t count = 0;
        for (uint32_t i = 0; i < NAR_VK_MAX_FRAMES_IN_FLIGHT; ++i)
            if (swapchain->frames[i].command_buffer != NULL) buffers[count++] = swapchain->frames[i].command_buffer;
        if (count > 0) free_command_buffers(session->device, session->command_pool, count, buffers);
    }
    if (destroy_framebuffer != NULL)
        for (uint32_t i = 0; i < swapchain->image_count; ++i)
            if (swapchain->framebuffers[i] != NULL) destroy_framebuffer(session->device, swapchain->framebuffers[i], NULL);
    if (destroy_render_pass != NULL && swapchain->render_pass != NULL)
        destroy_render_pass(session->device, swapchain->render_pass, NULL);
    if (destroy_view != NULL)
        for (uint32_t i = 0; i < swapchain->image_view_count; ++i)
            if (swapchain->image_views[i] != NULL) destroy_view(session->device, swapchain->image_views[i], NULL);
    if (session->destroy_swapchain != NULL && swapchain->handle != NULL)
        session->destroy_swapchain(session->device, swapchain->handle, NULL);
    if (free_struct) free(swapchain);
}

void nar_vulkan_swapchain_destroy(nar_vulkan_session *session, nar_vulkan_swapchain *swapchain) {
    destroy_swapchain_resources(session, swapchain, 1);
}

int32_t nar_vulkan_swapchain_recreate(nar_vulkan_session *session, nar_vulkan_swapchain *swapchain,
                                      uint32_t width, uint32_t height) {
    if (session == NULL || swapchain == NULL) return NAR_VULKAN_WSI_STATUS_INVALID;
    if (width == 0 || height == 0) return NAR_VULKAN_WSI_STATUS_PAUSED;
    nar_vulkan_swapchain *replacement = nar_vulkan_swapchain_create(session, width, height);
    if (replacement == NULL) return NAR_VULKAN_WSI_STATUS_ERROR;
    if (session->swapchains != replacement) {
        nar_vulkan_swapchain_destroy(session, replacement);
        return NAR_VULKAN_WSI_STATUS_ERROR;
    }
    session->swapchains = replacement->next;
    struct nar_vulkan_swapchain old = *swapchain;
    nar_vulkan_swapchain *list_next = swapchain->next;
    *swapchain = *replacement;
    swapchain->next = list_next;
    free(replacement);
    destroy_swapchain_resources(session, &old, 0);
    return NAR_VULKAN_WSI_STATUS_OK;
}

int32_t nar_vulkan_swapchain_acquire(nar_vulkan_session *session, nar_vulkan_swapchain *swapchain,
                                     uint32_t *out_image_index) {
    if (session == NULL || swapchain == NULL || out_image_index == NULL ||
            session->acquire_next_image == NULL || swapchain->acquired) return NAR_VULKAN_WSI_STATUS_INVALID;
    struct nar_vulkan_swapchain_frame *frame = &swapchain->frames[swapchain->frame_index];
    nar_vk_wait_for_fences_fn wait =
            (nar_vk_wait_for_fences_fn)dp(session->gdpa, session->device, "vkWaitForFences");
    if (wait == NULL || frame->fence == NULL || frame->image_available == NULL)
        return NAR_VULKAN_WSI_STATUS_UNSUPPORTED;
    nar_vk_result result = wait(session->device, 1, &frame->fence, 1, UINT64_MAX);
    if (result != NAR_VK_SUCCESS) return wsi_status_from_vk(result);
    result = session->acquire_next_image(session->device, swapchain->handle, UINT64_MAX,
            frame->image_available, NULL, out_image_index);
    int32_t status = wsi_status_from_vk(result);
    if (status == NAR_VULKAN_WSI_STATUS_OK || status == NAR_VULKAN_WSI_STATUS_SUBOPTIMAL) {
        swapchain->acquired = 1;
        swapchain->acquired_image = *out_image_index;
    }
    return status;
}

int32_t nar_vulkan_swapchain_present(nar_vulkan_session *session, nar_vulkan_swapchain *swapchain,
                                     uint32_t image_index) {
    if (session == NULL || swapchain == NULL || !swapchain->acquired ||
            image_index >= swapchain->image_count || image_index != swapchain->acquired_image ||
            session->queue_present == NULL) return NAR_VULKAN_WSI_STATUS_INVALID;
    struct nar_vulkan_swapchain_frame *frame = &swapchain->frames[swapchain->frame_index];
    nar_vk_reset_command_buffer_fn reset_command_buffer =
            (nar_vk_reset_command_buffer_fn)dp(session->gdpa, session->device, "vkResetCommandBuffer");
    nar_vk_begin_command_buffer_fn begin_command_buffer =
            (nar_vk_begin_command_buffer_fn)dp(session->gdpa, session->device, "vkBeginCommandBuffer");
    nar_vk_end_command_buffer_fn end_command_buffer =
            (nar_vk_end_command_buffer_fn)dp(session->gdpa, session->device, "vkEndCommandBuffer");
    nar_vk_cmd_begin_render_pass_fn begin_render_pass =
            (nar_vk_cmd_begin_render_pass_fn)dp(session->gdpa, session->device, "vkCmdBeginRenderPass");
    nar_vk_cmd_end_render_pass_fn end_render_pass =
            (nar_vk_cmd_end_render_pass_fn)dp(session->gdpa, session->device, "vkCmdEndRenderPass");
    nar_vk_queue_submit_fn queue_submit =
            (nar_vk_queue_submit_fn)dp(session->gdpa, session->device, "vkQueueSubmit");
    if (reset_command_buffer == NULL || begin_command_buffer == NULL || end_command_buffer == NULL ||
            begin_render_pass == NULL || end_render_pass == NULL || queue_submit == NULL ||
            frame->command_buffer == NULL || frame->render_finished == NULL || swapchain->render_pass == NULL)
        return NAR_VULKAN_WSI_STATUS_UNSUPPORTED;
    if (reset_command_buffer(frame->command_buffer, 0) != NAR_VK_SUCCESS) return NAR_VULKAN_WSI_STATUS_ERROR;
    nar_vk_command_buffer_begin_info begin_info;
    memset(&begin_info, 0, sizeof(begin_info));
    begin_info.s_type = NAR_VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin_info.flags = NAR_VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (begin_command_buffer(frame->command_buffer, &begin_info) != NAR_VK_SUCCESS) return NAR_VULKAN_WSI_STATUS_ERROR;
    nar_vk_clear_value clear_value = {{ 0.0f, 0.0f, 0.0f, 1.0f }};
    nar_vk_render_pass_begin_info pass_info;
    memset(&pass_info, 0, sizeof(pass_info));
    pass_info.s_type = NAR_VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    pass_info.render_pass = swapchain->render_pass;
    pass_info.framebuffer = swapchain->framebuffers[image_index];
    pass_info.render_area.width = swapchain->width;
    pass_info.render_area.height = swapchain->height;
    pass_info.clear_value_count = 1;
    pass_info.clear_values = &clear_value;
    begin_render_pass(frame->command_buffer, &pass_info, NAR_VK_SUBPASS_CONTENTS_INLINE);
    end_render_pass(frame->command_buffer);
    if (end_command_buffer(frame->command_buffer) != NAR_VK_SUCCESS) return NAR_VULKAN_WSI_STATUS_ERROR;
    nar_vk_reset_fences_fn reset_fences =
            (nar_vk_reset_fences_fn)dp(session->gdpa, session->device, "vkResetFences");
    if (reset_fences == NULL || reset_fences(session->device, 1, &frame->fence) != NAR_VK_SUCCESS)
        return NAR_VULKAN_WSI_STATUS_ERROR;
    uint32_t wait_stage = NAR_VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    nar_vk_submit_info submit;
    memset(&submit, 0, sizeof(submit));
    submit.s_type = NAR_VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.wait_semaphore_count = 1;
    submit.wait_semaphores = &frame->image_available;
    submit.wait_dst_stage_mask = &wait_stage;
    submit.command_buffer_count = 1;
    submit.command_buffers = &frame->command_buffer;
    submit.signal_semaphore_count = 1;
    submit.signal_semaphores = &frame->render_finished;
    nar_vk_result result = queue_submit(session->graphics_queue, 1, &submit, frame->fence);
    if (result != NAR_VK_SUCCESS) return wsi_status_from_vk(result);
    nar_vk_swapchain handle = swapchain->handle;
    nar_vk_present_info present;
    memset(&present, 0, sizeof(present));
    present.s_type = NAR_VK_STRUCTURE_TYPE_PRESENT_INFO;
    present.wait_semaphore_count = 1;
    present.wait_semaphores = &frame->render_finished;
    present.swapchain_count = 1;
    present.swapchains = &handle;
    present.image_indices = &image_index;
    result = session->queue_present(session->present_queue, &present);
    int32_t status = wsi_status_from_vk(result);
    swapchain->acquired = 0;
    swapchain->frame_index = (swapchain->frame_index + 1u) % NAR_VK_MAX_FRAMES_IN_FLIGHT;
    return status;
}

uint64_t nar_vulkan_wsi_last_error(const nar_vulkan_session *session, char *dst, uint64_t capacity) {
    return nar_vulkan_session_last_error(session, dst, capacity);
}

/* =========================================================================
 * Offscreen rendering — implementation
 * ========================================================================= */

/* Resolve a device-level entry point via vkGetDeviceProcAddr. */
static void *dp(nar_vk_get_device_proc_addr_fn gdpa, nar_vk_device dev, const char *name)
{
    return gdpa == NULL ? NULL : gdpa(dev, name);
}

/* Choose the first memory type index that satisfies type_bits and flags.
 * Returns UINT32_MAX when no matching type is found. */
static uint32_t pick_memory_type(
    const nar_vk_physical_device_memory_properties *mp,
    uint32_t type_bits, uint32_t required_flags)
{
    for (uint32_t i = 0; i < mp->memory_type_count && i < 32; ++i)
        if ((type_bits & (1u << i)) &&
            (mp->memory_types[i].property_flags & required_flags) == required_flags)
            return i;
    return UINT32_MAX;
}

nar_vulkan_offscreen_image *nar_vulkan_offscreen_image_create(
        nar_vulkan_session *session, uint32_t width, uint32_t height)
{
    if (session == NULL || width == 0 || height == 0) return NULL;
    nar_vk_device              dev  = session->device;
    nar_vk_get_device_proc_addr_fn gdpa = session->gdpa;
    nar_vk_physical_device     phys = session->physical_device;

    nar_vk_create_image_fn                   f_ci   = (nar_vk_create_image_fn)                   dp(gdpa,dev,"vkCreateImage");
    nar_vk_destroy_image_fn                  f_dimg = (nar_vk_destroy_image_fn)                  dp(gdpa,dev,"vkDestroyImage");
    nar_vk_get_image_memory_requirements_fn  f_imr  = (nar_vk_get_image_memory_requirements_fn)  dp(gdpa,dev,"vkGetImageMemoryRequirements");
    nar_vk_allocate_memory_fn                f_am   = (nar_vk_allocate_memory_fn)                dp(gdpa,dev,"vkAllocateMemory");
    nar_vk_free_memory_fn                    f_fm   = (nar_vk_free_memory_fn)                    dp(gdpa,dev,"vkFreeMemory");
    nar_vk_bind_image_memory_fn              f_bim  = (nar_vk_bind_image_memory_fn)              dp(gdpa,dev,"vkBindImageMemory");
    nar_vk_create_image_view_fn              f_civ  = (nar_vk_create_image_view_fn)              dp(gdpa,dev,"vkCreateImageView");
    nar_vk_destroy_image_view_fn             f_div  = (nar_vk_destroy_image_view_fn)             dp(gdpa,dev,"vkDestroyImageView");
    nar_vk_create_render_pass_fn             f_crp  = (nar_vk_create_render_pass_fn)             dp(gdpa,dev,"vkCreateRenderPass");
    nar_vk_destroy_render_pass_fn            f_drp  = (nar_vk_destroy_render_pass_fn)            dp(gdpa,dev,"vkDestroyRenderPass");
    nar_vk_create_framebuffer_fn             f_cfb  = (nar_vk_create_framebuffer_fn)             dp(gdpa,dev,"vkCreateFramebuffer");
    nar_vk_destroy_framebuffer_fn            f_dfb  = (nar_vk_destroy_framebuffer_fn)            dp(gdpa,dev,"vkDestroyFramebuffer");
    nar_vk_allocate_command_buffers_fn       f_acb  = (nar_vk_allocate_command_buffers_fn)       dp(gdpa,dev,"vkAllocateCommandBuffers");
    nar_vk_free_command_buffers_fn           f_fcb  = (nar_vk_free_command_buffers_fn)           dp(gdpa,dev,"vkFreeCommandBuffers");
    nar_vk_create_fence_fn                   f_cf   = (nar_vk_create_fence_fn)                   dp(gdpa,dev,"vkCreateFence");
    nar_vk_destroy_fence_fn                  f_df   = (nar_vk_destroy_fence_fn)                  dp(gdpa,dev,"vkDestroyFence");
    nar_vk_create_buffer_fn                  f_cbuf = (nar_vk_create_buffer_fn)                  dp(gdpa,dev,"vkCreateBuffer");
    nar_vk_destroy_buffer_fn                 f_dbuf = (nar_vk_destroy_buffer_fn)                 dp(gdpa,dev,"vkDestroyBuffer");
    nar_vk_get_buffer_memory_requirements_fn f_bmr  = (nar_vk_get_buffer_memory_requirements_fn) dp(gdpa,dev,"vkGetBufferMemoryRequirements");
    nar_vk_bind_buffer_memory_fn             f_bbm  = (nar_vk_bind_buffer_memory_fn)             dp(gdpa,dev,"vkBindBufferMemory");
    /* The session struct carries gdpa; use it to resolve the physical-device query
     * via the instance-level chain rather than going back to the global loader symbol. */
    nar_vk_get_physical_device_memory_properties_fn f_gmp = NULL;
    {
        /* Physical-device functions are resolved through the instance dispatch chain cached when
         * the session was created. A global-loader fallback remains for non-standard loaders. */
        if (session->gipa != NULL)
            f_gmp = (nar_vk_get_physical_device_memory_properties_fn)
                    session->gipa(session->instance, "vkGetPhysicalDeviceMemoryProperties");
        if (f_gmp == NULL)
            f_gmp = (nar_vk_get_physical_device_memory_properties_fn)
                    nar_vulkan_get_proc_address("vkGetPhysicalDeviceMemoryProperties");
    }

    /* Validate all required entry points up front. */
    if (!f_ci || !f_dimg || !f_imr || !f_am || !f_fm || !f_bim ||
        !f_civ || !f_div || !f_crp || !f_drp || !f_cfb || !f_dfb ||
        !f_acb || !f_fcb || !f_cf  || !f_df  || !f_cbuf|| !f_dbuf||
        !f_bmr || !f_bbm || !f_gmp) {
        copy_text(session->error, sizeof(session->error),
                  "offscreen: required device entry points unavailable");
        return NULL;
    }

    /* ------------------------------------------------------------------
     * 1. Create the device-local color-attachment image.
     * ------------------------------------------------------------------ */
    nar_vk_image_create_info img_ci;
    memset(&img_ci, 0, sizeof(img_ci));
    img_ci.s_type       = NAR_VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    img_ci.image_type   = NAR_VK_IMAGE_TYPE_2D;
    img_ci.format       = NAR_VK_FORMAT_B8G8R8A8_UNORM;
    img_ci.extent.width = width;
    img_ci.extent.height= height;
    img_ci.extent.depth = 1;
    img_ci.mip_levels   = 1;
    img_ci.array_layers = 1;
    img_ci.samples      = NAR_VK_SAMPLE_COUNT_1_BIT;
    img_ci.tiling       = NAR_VK_IMAGE_TILING_OPTIMAL;
    img_ci.usage        = NAR_VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                          NAR_VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    img_ci.sharing_mode = 0; /* VK_SHARING_MODE_EXCLUSIVE */
    img_ci.initial_layout = NAR_VK_IMAGE_LAYOUT_UNDEFINED;

    nar_vk_image image = NULL;
    if (f_ci(dev, &img_ci, NULL, &image) != NAR_VK_SUCCESS || image == NULL) {
        copy_text(session->error, sizeof(session->error), "offscreen: vkCreateImage failed");
        return NULL;
    }

    /* ------------------------------------------------------------------
     * 2. Allocate device-local memory for the image.
     * ------------------------------------------------------------------ */
    nar_vk_memory_requirements imr;
    memset(&imr, 0, sizeof(imr));
    f_imr(dev, image, &imr);

    nar_vk_physical_device_memory_properties mp;
    memset(&mp, 0, sizeof(mp));
    f_gmp(phys, &mp);

    uint32_t img_mt = pick_memory_type(&mp, imr.memory_type_bits,
                                       NAR_VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (img_mt == UINT32_MAX) {
        f_dimg(dev, image, NULL);
        copy_text(session->error, sizeof(session->error),
                  "offscreen: no device-local memory type for image");
        return NULL;
    }

    nar_vk_memory_allocate_info img_mai;
    memset(&img_mai, 0, sizeof(img_mai));
    img_mai.s_type           = NAR_VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    img_mai.allocation_size  = imr.size;
    img_mai.memory_type_index= img_mt;

    nar_vk_device_memory img_mem = NULL;
    if (f_am(dev, &img_mai, NULL, &img_mem) != NAR_VK_SUCCESS || img_mem == NULL) {
        f_dimg(dev, image, NULL);
        copy_text(session->error, sizeof(session->error),
                  "offscreen: vkAllocateMemory for image failed");
        return NULL;
    }
    if (f_bim(dev, image, img_mem, 0) != NAR_VK_SUCCESS) {
        f_fm(dev, img_mem, NULL);
        f_dimg(dev, image, NULL);
        copy_text(session->error, sizeof(session->error),
                  "offscreen: vkBindImageMemory failed");
        return NULL;
    }

    /* ------------------------------------------------------------------
     * 3. Image view.
     * ------------------------------------------------------------------ */
    nar_vk_image_view_create_info ivc;
    memset(&ivc, 0, sizeof(ivc));
    ivc.s_type    = NAR_VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    ivc.image     = image;
    ivc.view_type = NAR_VK_IMAGE_VIEW_TYPE_2D;
    ivc.format    = NAR_VK_FORMAT_B8G8R8A8_UNORM;
    ivc.components.r = ivc.components.g = ivc.components.b = ivc.components.a = 0; /* identity */
    ivc.subresource_range.aspect_mask      = NAR_VK_IMAGE_ASPECT_COLOR_BIT;
    ivc.subresource_range.base_mip_level   = 0;
    ivc.subresource_range.level_count      = 1;
    ivc.subresource_range.base_array_layer = 0;
    ivc.subresource_range.layer_count      = 1;

    nar_vk_image_view view = NULL;
    if (f_civ(dev, &ivc, NULL, &view) != NAR_VK_SUCCESS || view == NULL) {
        f_fm(dev, img_mem, NULL);
        f_dimg(dev, image, NULL);
        copy_text(session->error, sizeof(session->error),
                  "offscreen: vkCreateImageView failed");
        return NULL;
    }

    /* ------------------------------------------------------------------
     * 4. Render pass (single color attachment, CLEAR on load, STORE on store).
     * ------------------------------------------------------------------ */
    nar_vk_attachment_description att;
    memset(&att, 0, sizeof(att));
    att.format          = NAR_VK_FORMAT_B8G8R8A8_UNORM;
    att.samples         = NAR_VK_SAMPLE_COUNT_1_BIT;
    att.load_op         = NAR_VK_ATTACHMENT_LOAD_OP_CLEAR;
    att.store_op        = NAR_VK_ATTACHMENT_STORE_OP_STORE;
    att.stencil_load_op = 2; /* VK_ATTACHMENT_LOAD_OP_DONT_CARE */
    att.stencil_store_op= 1; /* VK_ATTACHMENT_STORE_OP_DONT_CARE */
    att.initial_layout  = NAR_VK_IMAGE_LAYOUT_UNDEFINED;
    att.final_layout    = NAR_VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

    nar_vk_attachment_reference color_ref;
    color_ref.attachment = 0;
    color_ref.layout     = NAR_VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    nar_vk_subpass_description subpass;
    memset(&subpass, 0, sizeof(subpass));
    subpass.pipeline_bind_point    = NAR_VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.color_attachment_count = 1;
    subpass.color_attachments      = &color_ref;

    nar_vk_render_pass_create_info rpci;
    memset(&rpci, 0, sizeof(rpci));
    rpci.s_type          = NAR_VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    rpci.attachment_count= 1;
    rpci.attachments     = &att;
    rpci.subpass_count   = 1;
    rpci.subpasses       = &subpass;

    nar_vk_render_pass rp = NULL;
    if (f_crp(dev, &rpci, NULL, &rp) != NAR_VK_SUCCESS || rp == NULL) {
        f_div(dev, view, NULL);
        f_fm(dev, img_mem, NULL);
        f_dimg(dev, image, NULL);
        copy_text(session->error, sizeof(session->error),
                  "offscreen: vkCreateRenderPass failed");
        return NULL;
    }

    /* ------------------------------------------------------------------
     * 5. Framebuffer.
     * ------------------------------------------------------------------ */
    nar_vk_framebuffer_create_info fbci;
    memset(&fbci, 0, sizeof(fbci));
    fbci.s_type           = NAR_VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    fbci.render_pass      = rp;
    fbci.attachment_count = 1;
    fbci.attachments      = &view;
    fbci.width            = width;
    fbci.height           = height;
    fbci.layers           = 1;

    nar_vk_framebuffer fb = NULL;
    if (f_cfb(dev, &fbci, NULL, &fb) != NAR_VK_SUCCESS || fb == NULL) {
        f_drp(dev, rp, NULL);
        f_div(dev, view, NULL);
        f_fm(dev, img_mem, NULL);
        f_dimg(dev, image, NULL);
        copy_text(session->error, sizeof(session->error),
                  "offscreen: vkCreateFramebuffer failed");
        return NULL;
    }

    /* ------------------------------------------------------------------
     * 6. Host-visible readback buffer.
     * ------------------------------------------------------------------ */
    uint64_t rb_size = (uint64_t)width * height * 4;

    nar_vk_buffer_create_info bcf;
    memset(&bcf, 0, sizeof(bcf));
    bcf.s_type       = NAR_VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bcf.size         = rb_size;
    bcf.usage        = NAR_VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bcf.sharing_mode = 0;

    nar_vk_buffer rb_buf = NULL;
    if (f_cbuf(dev, &bcf, NULL, &rb_buf) != NAR_VK_SUCCESS || rb_buf == NULL) {
        f_dfb(dev, fb, NULL);
        f_drp(dev, rp, NULL);
        f_div(dev, view, NULL);
        f_fm(dev, img_mem, NULL);
        f_dimg(dev, image, NULL);
        copy_text(session->error, sizeof(session->error),
                  "offscreen: vkCreateBuffer for readback failed");
        return NULL;
    }

    nar_vk_memory_requirements bmr;
    memset(&bmr, 0, sizeof(bmr));
    f_bmr(dev, rb_buf, &bmr);

    uint32_t rb_mt = pick_memory_type(&mp, bmr.memory_type_bits,
                                      NAR_VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                      NAR_VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (rb_mt == UINT32_MAX) {
        /* Fall back to just host-visible if coherent is not available. */
        rb_mt = pick_memory_type(&mp, bmr.memory_type_bits,
                                 NAR_VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
    }
    if (rb_mt == UINT32_MAX) {
        f_dbuf(dev, rb_buf, NULL);
        f_dfb(dev, fb, NULL);
        f_drp(dev, rp, NULL);
        f_div(dev, view, NULL);
        f_fm(dev, img_mem, NULL);
        f_dimg(dev, image, NULL);
        copy_text(session->error, sizeof(session->error),
                  "offscreen: no host-visible memory type for readback buffer");
        return NULL;
    }

    nar_vk_memory_allocate_info rb_mai;
    memset(&rb_mai, 0, sizeof(rb_mai));
    rb_mai.s_type            = NAR_VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    rb_mai.allocation_size   = bmr.size;
    rb_mai.memory_type_index = rb_mt;

    nar_vk_device_memory rb_mem = NULL;
    if (f_am(dev, &rb_mai, NULL, &rb_mem) != NAR_VK_SUCCESS || rb_mem == NULL) {
        f_dbuf(dev, rb_buf, NULL);
        f_dfb(dev, fb, NULL);
        f_drp(dev, rp, NULL);
        f_div(dev, view, NULL);
        f_fm(dev, img_mem, NULL);
        f_dimg(dev, image, NULL);
        copy_text(session->error, sizeof(session->error),
                  "offscreen: vkAllocateMemory for readback buffer failed");
        return NULL;
    }
    if (f_bbm(dev, rb_buf, rb_mem, 0) != NAR_VK_SUCCESS) {
        f_fm(dev, rb_mem, NULL);
        f_dbuf(dev, rb_buf, NULL);
        f_dfb(dev, fb, NULL);
        f_drp(dev, rp, NULL);
        f_div(dev, view, NULL);
        f_fm(dev, img_mem, NULL);
        f_dimg(dev, image, NULL);
        copy_text(session->error, sizeof(session->error),
                  "offscreen: vkBindBufferMemory for readback failed");
        return NULL;
    }

    /* ------------------------------------------------------------------
     * 7. Command buffer.
     * ------------------------------------------------------------------ */
    nar_vk_command_buffer_allocate_info cbai;
    memset(&cbai, 0, sizeof(cbai));
    cbai.s_type               = NAR_VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbai.command_pool         = session->command_pool;
    cbai.level                = NAR_VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbai.command_buffer_count = 1;

    nar_vk_command_buffer cb = NULL;
    if (f_acb(dev, &cbai, &cb) != NAR_VK_SUCCESS || cb == NULL) {
        f_fm(dev, rb_mem, NULL);
        f_dbuf(dev, rb_buf, NULL);
        f_dfb(dev, fb, NULL);
        f_drp(dev, rp, NULL);
        f_div(dev, view, NULL);
        f_fm(dev, img_mem, NULL);
        f_dimg(dev, image, NULL);
        copy_text(session->error, sizeof(session->error),
                  "offscreen: vkAllocateCommandBuffers failed");
        return NULL;
    }

    /* ------------------------------------------------------------------
     * 8. Fence (unsignaled).
     * ------------------------------------------------------------------ */
    nar_vk_fence_create_info fci;
    memset(&fci, 0, sizeof(fci));
    fci.s_type = NAR_VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    /* flags = 0: start unsignaled */

    nar_vk_fence fence = NULL;
    if (f_cf(dev, &fci, NULL, &fence) != NAR_VK_SUCCESS || fence == NULL) {
        f_fcb(dev, session->command_pool, 1, &cb);
        f_fm(dev, rb_mem, NULL);
        f_dbuf(dev, rb_buf, NULL);
        f_dfb(dev, fb, NULL);
        f_drp(dev, rp, NULL);
        f_div(dev, view, NULL);
        f_fm(dev, img_mem, NULL);
        f_dimg(dev, image, NULL);
        copy_text(session->error, sizeof(session->error),
                  "offscreen: vkCreateFence failed");
        return NULL;
    }

    /* ------------------------------------------------------------------
     * 9. Pack everything into the opaque struct.
     * ------------------------------------------------------------------ */
    struct nar_vulkan_offscreen_image *img = calloc(1, sizeof(*img));
    if (img == NULL) {
        f_df(dev, fence, NULL);
        f_fcb(dev, session->command_pool, 1, &cb);
        f_fm(dev, rb_mem, NULL);
        f_dbuf(dev, rb_buf, NULL);
        f_dfb(dev, fb, NULL);
        f_drp(dev, rp, NULL);
        f_div(dev, view, NULL);
        f_fm(dev, img_mem, NULL);
        f_dimg(dev, image, NULL);
        copy_text(session->error, sizeof(session->error),
                  "offscreen: out of memory");
        return NULL;
    }
    img->width          = width;
    img->height         = height;
    img->image          = image;
    img->image_memory   = img_mem;
    img->image_view     = view;
    img->render_pass    = rp;
    img->framebuffer    = fb;
    img->readback_buffer= rb_buf;
    img->readback_memory= rb_mem;
    img->readback_size  = rb_size;
    img->command_buffer = cb;
    img->fence          = fence;
    img->fence_submitted= 0;
    img->gdpa           = gdpa;
    img->device         = dev;
    return img;
}

void nar_vulkan_offscreen_image_destroy(
        nar_vulkan_session *session,
        nar_vulkan_offscreen_image *image)
{
    if (image == NULL) return;
    nar_vk_device dev  = image->device;
    nar_vk_get_device_proc_addr_fn gdpa = image->gdpa;

    /* If a submission is in flight, wait for it before freeing GPU resources. */
    if (image->fence_submitted && image->fence != NULL) {
        nar_vk_wait_for_fences_fn f_wff = (nar_vk_wait_for_fences_fn)dp(gdpa, dev, "vkWaitForFences");
        if (f_wff != NULL) f_wff(dev, 1, &image->fence, 1 /* waitAll */, UINT64_MAX);
    }

    nar_vk_destroy_fence_fn           f_df  = (nar_vk_destroy_fence_fn)          dp(gdpa, dev, "vkDestroyFence");
    nar_vk_free_command_buffers_fn    f_fcb = (nar_vk_free_command_buffers_fn)   dp(gdpa, dev, "vkFreeCommandBuffers");
    nar_vk_destroy_buffer_fn          f_db  = (nar_vk_destroy_buffer_fn)         dp(gdpa, dev, "vkDestroyBuffer");
    nar_vk_free_memory_fn             f_fm  = (nar_vk_free_memory_fn)            dp(gdpa, dev, "vkFreeMemory");
    nar_vk_destroy_framebuffer_fn     f_dfb = (nar_vk_destroy_framebuffer_fn)    dp(gdpa, dev, "vkDestroyFramebuffer");
    nar_vk_destroy_render_pass_fn     f_drp = (nar_vk_destroy_render_pass_fn)    dp(gdpa, dev, "vkDestroyRenderPass");
    nar_vk_destroy_image_view_fn      f_div = (nar_vk_destroy_image_view_fn)     dp(gdpa, dev, "vkDestroyImageView");
    nar_vk_destroy_image_fn           f_di  = (nar_vk_destroy_image_fn)          dp(gdpa, dev, "vkDestroyImage");

    nar_vk_command_pool pool = (session != NULL) ? session->command_pool : NULL;

    if (f_df  && image->fence)          f_df(dev, image->fence, NULL);
    if (f_fcb && pool && image->command_buffer)
        f_fcb(dev, pool, 1, &image->command_buffer);
    if (f_fm  && image->readback_memory) f_fm(dev, image->readback_memory, NULL);
    if (f_db  && image->readback_buffer) f_db(dev, image->readback_buffer, NULL);
    if (f_dfb && image->framebuffer)     f_dfb(dev, image->framebuffer, NULL);
    if (f_drp && image->render_pass)     f_drp(dev, image->render_pass, NULL);
    if (f_div && image->image_view)      f_div(dev, image->image_view, NULL);
    if (f_fm  && image->image_memory)    f_fm(dev, image->image_memory, NULL);
    if (f_di  && image->image)           f_di(dev, image->image, NULL);

    free(image);
}

int32_t nar_vulkan_offscreen_render_begin(
        nar_vulkan_session *session,
        nar_vulkan_offscreen_image *image,
        void **out_command_buffer)
{
    if (session == NULL || image == NULL) return -1;
    nar_vk_device dev  = image->device;
    nar_vk_get_device_proc_addr_fn gdpa = image->gdpa;

    nar_vk_reset_fences_fn          f_rf  = (nar_vk_reset_fences_fn)         dp(gdpa, dev, "vkResetFences");
    nar_vk_reset_command_buffer_fn  f_rcb = (nar_vk_reset_command_buffer_fn) dp(gdpa, dev, "vkResetCommandBuffer");
    nar_vk_begin_command_buffer_fn  f_bcb = (nar_vk_begin_command_buffer_fn) dp(gdpa, dev, "vkBeginCommandBuffer");
    nar_vk_cmd_begin_render_pass_fn f_brp = (nar_vk_cmd_begin_render_pass_fn)dp(gdpa, dev, "vkCmdBeginRenderPass");

    if (!f_rf || !f_rcb || !f_bcb || !f_brp) {
        copy_text(session->error, sizeof(session->error),
                  "offscreen render_begin: required entry points unavailable");
        return -1;
    }

    /* If the previous frame's fence is still pending wait for it to avoid
     * resetting the command buffer while the GPU is still reading it. */
    if (image->fence_submitted) {
        nar_vk_wait_for_fences_fn f_wff = (nar_vk_wait_for_fences_fn)dp(gdpa, dev, "vkWaitForFences");
        if (f_wff == NULL || f_wff(dev, 1, &image->fence, 1, UINT64_MAX) != NAR_VK_SUCCESS) {
            copy_text(session->error, sizeof(session->error),
                      "offscreen render_begin: vkWaitForFences failed");
            return -1;
        }
        image->fence_submitted = 0;
    }

    /* Reset fence to unsignaled for this frame. */
    if (f_rf(dev, 1, &image->fence) != NAR_VK_SUCCESS) {
        copy_text(session->error, sizeof(session->error),
                  "offscreen render_begin: vkResetFences failed");
        return -1;
    }

    /* Reset and begin the command buffer. */
    if (f_rcb(image->command_buffer, 0) != NAR_VK_SUCCESS) {
        copy_text(session->error, sizeof(session->error),
                  "offscreen render_begin: vkResetCommandBuffer failed");
        return -1;
    }

    nar_vk_command_buffer_begin_info cbbi;
    memset(&cbbi, 0, sizeof(cbbi));
    cbbi.s_type = NAR_VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    cbbi.flags  = NAR_VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

    if (f_bcb(image->command_buffer, &cbbi) != NAR_VK_SUCCESS) {
        copy_text(session->error, sizeof(session->error),
                  "offscreen render_begin: vkBeginCommandBuffer failed");
        return -1;
    }

    /* Begin the render pass with a black clear color. */
    nar_vk_clear_value clear;
    memset(&clear, 0, sizeof(clear)); /* RGBA = 0,0,0,0 */

    nar_vk_render_pass_begin_info rpbi;
    memset(&rpbi, 0, sizeof(rpbi));
    rpbi.s_type           = NAR_VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpbi.render_pass      = image->render_pass;
    rpbi.framebuffer      = image->framebuffer;
    rpbi.render_area.x    = 0;
    rpbi.render_area.y    = 0;
    rpbi.render_area.width = image->width;
    rpbi.render_area.height= image->height;
    rpbi.clear_value_count = 1;
    rpbi.clear_values      = &clear;

    f_brp(image->command_buffer, &rpbi, NAR_VK_SUBPASS_CONTENTS_INLINE);

    if (out_command_buffer != NULL)
        *out_command_buffer = image->command_buffer;
    return NAR_OFFSCREEN_RESULT_OK;
}

int32_t nar_vulkan_offscreen_render_end(
        nar_vulkan_session *session,
        nar_vulkan_offscreen_image *image)
{
    if (session == NULL || image == NULL) return -1;
    nar_vk_device dev  = image->device;
    nar_vk_get_device_proc_addr_fn gdpa = image->gdpa;

    nar_vk_cmd_end_render_pass_fn      f_erp = (nar_vk_cmd_end_render_pass_fn)     dp(gdpa, dev, "vkCmdEndRenderPass");
    nar_vk_cmd_pipeline_barrier_fn     f_pb  = (nar_vk_cmd_pipeline_barrier_fn)    dp(gdpa, dev, "vkCmdPipelineBarrier");
    nar_vk_cmd_copy_image_to_buffer_fn f_cib = (nar_vk_cmd_copy_image_to_buffer_fn)dp(gdpa, dev, "vkCmdCopyImageToBuffer");
    nar_vk_end_command_buffer_fn       f_ecb = (nar_vk_end_command_buffer_fn)      dp(gdpa, dev, "vkEndCommandBuffer");
    nar_vk_queue_submit_fn             f_qs  = (nar_vk_queue_submit_fn)            dp(gdpa, dev, "vkQueueSubmit");

    if (!f_erp || !f_pb || !f_cib || !f_ecb || !f_qs) {
        copy_text(session->error, sizeof(session->error),
                  "offscreen render_end: required entry points unavailable");
        return -1;
    }

    /* End the render pass. */
    f_erp(image->command_buffer);

    /* Barrier: color-attachment-write -> transfer-read, transition to TRANSFER_SRC_OPTIMAL.
     * The render pass final_layout already transitions to TRANSFER_SRC_OPTIMAL, so we only
     * need to synchronize access; the old_layout here matches final_layout. */
    nar_vk_image_memory_barrier barrier;
    memset(&barrier, 0, sizeof(barrier));
    barrier.s_type                           = NAR_VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.src_access_mask                  = NAR_VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    barrier.dst_access_mask                  = NAR_VK_ACCESS_TRANSFER_READ_BIT;
    barrier.old_layout                       = NAR_VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    barrier.new_layout                       = NAR_VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    barrier.src_queue_family_index           = NAR_VK_QUEUE_FAMILY_IGNORED;
    barrier.dst_queue_family_index           = NAR_VK_QUEUE_FAMILY_IGNORED;
    barrier.image                            = image->image;
    barrier.subresource_range.aspect_mask    = NAR_VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresource_range.level_count    = 1;
    barrier.subresource_range.layer_count    = 1;

    f_pb(image->command_buffer,
         NAR_VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
         NAR_VK_PIPELINE_STAGE_TRANSFER_BIT,
         0, 0, NULL, 0, NULL, 1, &barrier);

    /* Copy the image into the host-visible readback buffer. */
    nar_vk_buffer_image_copy region;
    memset(&region, 0, sizeof(region));
    region.buffer_offset                    = 0;
    region.buffer_row_length                = 0; /* tightly packed */
    region.buffer_image_height              = 0;
    region.image_subresource.aspect_mask    = NAR_VK_IMAGE_ASPECT_COLOR_BIT;
    region.image_subresource.mip_level      = 0;
    region.image_subresource.base_array_layer= 0;
    region.image_subresource.layer_count    = 1;
    region.image_offset_x                   = 0;
    region.image_offset_y                   = 0;
    region.image_offset_z                   = 0;
    region.image_extent.width               = image->width;
    region.image_extent.height              = image->height;
    region.image_extent.depth               = 1;

    f_cib(image->command_buffer, image->image,
          NAR_VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
          image->readback_buffer, 1, &region);

    /* Barrier: transfer-write -> host-read on the readback BUFFER (not the image).
     * Uses VkBufferMemoryBarrier so the GPU write to the readback buffer is made
     * visible to the CPU before we map it in nar_vulkan_offscreen_readback. */
    nar_vk_buffer_memory_barrier buf_barrier;
    memset(&buf_barrier, 0, sizeof(buf_barrier));
    buf_barrier.s_type                = NAR_VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    buf_barrier.src_access_mask       = NAR_VK_ACCESS_TRANSFER_WRITE_BIT;
    buf_barrier.dst_access_mask       = NAR_VK_ACCESS_HOST_READ_BIT;
    buf_barrier.src_queue_family_index= NAR_VK_QUEUE_FAMILY_IGNORED;
    buf_barrier.dst_queue_family_index= NAR_VK_QUEUE_FAMILY_IGNORED;
    buf_barrier.buffer                = image->readback_buffer;
    buf_barrier.offset                = 0;
    buf_barrier.size                  = image->readback_size; /* VK_WHOLE_SIZE would be ~0ull */

    /* vkCmdPipelineBarrier signature:
     *   (cb, srcStage, dstStage, depFlags,
     *    memBarrierCount, pMemBarriers,
     *    bufBarrierCount, pBufBarriers,
     *    imgBarrierCount, pImgBarriers) */
    f_pb(image->command_buffer,
         NAR_VK_PIPELINE_STAGE_TRANSFER_BIT,
         NAR_VK_PIPELINE_STAGE_HOST_BIT,
         0,
         0, NULL,
         1, (const void *)&buf_barrier,
         0, NULL);

    if (f_ecb(image->command_buffer) != NAR_VK_SUCCESS) {
        copy_text(session->error, sizeof(session->error),
                  "offscreen render_end: vkEndCommandBuffer failed");
        return -1;
    }

    /* Submit. */
    nar_vk_submit_info si;
    memset(&si, 0, sizeof(si));
    si.s_type               = NAR_VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.command_buffer_count = 1;
    si.command_buffers      = &image->command_buffer;

    if (f_qs(session->graphics_queue, 1, &si, image->fence) != NAR_VK_SUCCESS) {
        copy_text(session->error, sizeof(session->error),
                  "offscreen render_end: vkQueueSubmit failed");
        return -1;
    }

    image->fence_submitted = 1;
    return NAR_OFFSCREEN_RESULT_OK;
}

/* =========================================================================
 * Vertex buffer resource ABI — implementation
 * =========================================================================
 *
 * Opaque struct: one host-visible VkBuffer plus its backing VkDeviceMemory.
 * Prefers HOST_VISIBLE | HOST_COHERENT; falls back to HOST_VISIBLE alone when
 * the coherent flag is unavailable (rare but legal on some implementations).
 */
struct nar_vulkan_buffer {
    nar_vk_buffer           buffer;
    nar_vk_device_memory    memory;
    uint64_t                size;
    nar_vk_device           device;
    nar_vk_get_device_proc_addr_fn gdpa;
};

nar_vulkan_buffer *nar_vulkan_buffer_create(
        nar_vulkan_session *session, uint64_t size)
{
    if (session == NULL || size == 0) return NULL;

    nar_vk_device dev = session->device;
    nar_vk_get_device_proc_addr_fn gdpa = session->gdpa;

    nar_vk_create_buffer_fn                  f_cb  = (nar_vk_create_buffer_fn)                 dp(gdpa, dev, "vkCreateBuffer");
    nar_vk_destroy_buffer_fn                 f_db  = (nar_vk_destroy_buffer_fn)                dp(gdpa, dev, "vkDestroyBuffer");
    nar_vk_get_buffer_memory_requirements_fn f_bmr = (nar_vk_get_buffer_memory_requirements_fn)dp(gdpa, dev, "vkGetBufferMemoryRequirements");
    nar_vk_allocate_memory_fn                f_am  = (nar_vk_allocate_memory_fn)               dp(gdpa, dev, "vkAllocateMemory");
    nar_vk_free_memory_fn                    f_fm  = (nar_vk_free_memory_fn)                   dp(gdpa, dev, "vkFreeMemory");
    nar_vk_bind_buffer_memory_fn             f_bbm = (nar_vk_bind_buffer_memory_fn)            dp(gdpa, dev, "vkBindBufferMemory");

    if (!f_cb || !f_db || !f_bmr || !f_am || !f_fm || !f_bbm) {
        copy_text(session->error, sizeof(session->error),
                  "buffer_create: required device entry points unavailable");
        return NULL;
    }

    /* Physical-device memory properties for type selection. */
    nar_vk_physical_device_memory_properties mp;
    memset(&mp, 0, sizeof(mp));
    {
        nar_vk_get_instance_proc_addr_fn gipa =
            (nar_vk_get_instance_proc_addr_fn)nar_vulkan_get_proc_address("vkGetInstanceProcAddr");
        nar_vk_get_physical_device_memory_properties_fn f_gmp = NULL;
        if (gipa != NULL)
            f_gmp = (nar_vk_get_physical_device_memory_properties_fn)
                    gipa(session->instance, "vkGetPhysicalDeviceMemoryProperties");
        if (f_gmp == NULL)
            f_gmp = (nar_vk_get_physical_device_memory_properties_fn)
                    nar_vulkan_get_proc_address("vkGetPhysicalDeviceMemoryProperties");
        if (f_gmp == NULL) {
            copy_text(session->error, sizeof(session->error),
                      "buffer_create: vkGetPhysicalDeviceMemoryProperties unavailable");
            return NULL;
        }
        f_gmp(session->physical_device, &mp);
    }

    /* Create the buffer. */
    nar_vk_buffer_create_info bci;
    memset(&bci, 0, sizeof(bci));
    bci.s_type       = NAR_VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bci.size         = size;
    bci.usage        = NAR_VK_BUFFER_USAGE_VERTEX_BUFFER_BIT |
                       NAR_VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bci.sharing_mode = 0; /* VK_SHARING_MODE_EXCLUSIVE */

    nar_vk_buffer buf = NULL;
    if (f_cb(dev, &bci, NULL, &buf) != NAR_VK_SUCCESS || buf == NULL) {
        copy_text(session->error, sizeof(session->error),
                  "buffer_create: vkCreateBuffer failed");
        return NULL;
    }

    nar_vk_memory_requirements mr;
    memset(&mr, 0, sizeof(mr));
    f_bmr(dev, buf, &mr);

    /* Prefer coherent host-visible; fall back to plain host-visible. */
    uint32_t mt = pick_memory_type(&mp, mr.memory_type_bits,
                                   NAR_VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                   NAR_VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (mt == UINT32_MAX)
        mt = pick_memory_type(&mp, mr.memory_type_bits,
                              NAR_VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
    if (mt == UINT32_MAX) {
        f_db(dev, buf, NULL);
        copy_text(session->error, sizeof(session->error),
                  "buffer_create: no host-visible memory type available");
        return NULL;
    }

    nar_vk_memory_allocate_info mai;
    memset(&mai, 0, sizeof(mai));
    mai.s_type            = NAR_VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.allocation_size   = mr.size;
    mai.memory_type_index = mt;

    nar_vk_device_memory mem = NULL;
    if (f_am(dev, &mai, NULL, &mem) != NAR_VK_SUCCESS || mem == NULL) {
        f_db(dev, buf, NULL);
        copy_text(session->error, sizeof(session->error),
                  "buffer_create: vkAllocateMemory failed");
        return NULL;
    }

    if (f_bbm(dev, buf, mem, 0) != NAR_VK_SUCCESS) {
        f_fm(dev, mem, NULL);
        f_db(dev, buf, NULL);
        copy_text(session->error, sizeof(session->error),
                  "buffer_create: vkBindBufferMemory failed");
        return NULL;
    }

    struct nar_vulkan_buffer *vb = calloc(1, sizeof(*vb));
    if (vb == NULL) {
        f_fm(dev, mem, NULL);
        f_db(dev, buf, NULL);
        copy_text(session->error, sizeof(session->error),
                  "buffer_create: out of memory");
        return NULL;
    }
    vb->buffer = buf;
    vb->memory = mem;
    vb->size   = size;
    vb->device = dev;
    vb->gdpa   = gdpa;
    return vb;
}

void nar_vulkan_buffer_destroy(
        nar_vulkan_session *session, nar_vulkan_buffer *buffer)
{
    if (buffer == NULL) return;
    nar_vk_device dev = buffer->device;
    nar_vk_get_device_proc_addr_fn gdpa = buffer->gdpa;

    nar_vk_destroy_buffer_fn f_db = (nar_vk_destroy_buffer_fn)dp(gdpa, dev, "vkDestroyBuffer");
    nar_vk_free_memory_fn    f_fm = (nar_vk_free_memory_fn)   dp(gdpa, dev, "vkFreeMemory");

    if (f_db && buffer->buffer) f_db(dev, buffer->buffer, NULL);
    if (f_fm && buffer->memory) f_fm(dev, buffer->memory, NULL);
    free(buffer);
    (void)session; /* session context not needed for destroy */
}

int32_t nar_vulkan_buffer_upload(
        nar_vulkan_session *session,
        nar_vulkan_buffer *buffer,
        const void *data, uint64_t size)
{
    if (session == NULL || buffer == NULL) return -1;
    if (data == NULL && size > 0)          return -1;
    if (size == 0)                          return NAR_VULKAN_BUFFER_RESULT_OK;
    if (size > buffer->size) {
        copy_text(session->error, sizeof(session->error),
                  "buffer_upload: size exceeds buffer capacity");
        return -1;
    }

    nar_vk_device dev = buffer->device;
    nar_vk_get_device_proc_addr_fn gdpa = buffer->gdpa;

    nar_vk_map_memory_fn   f_mm = (nar_vk_map_memory_fn)  dp(gdpa, dev, "vkMapMemory");
    nar_vk_unmap_memory_fn f_um = (nar_vk_unmap_memory_fn)dp(gdpa, dev, "vkUnmapMemory");

    if (!f_mm || !f_um) {
        copy_text(session->error, sizeof(session->error),
                  "buffer_upload: vkMapMemory/vkUnmapMemory unavailable");
        return -1;
    }

    void *mapped = NULL;
    if (f_mm(dev, buffer->memory, 0, size, 0, &mapped) != NAR_VK_SUCCESS
            || mapped == NULL) {
        copy_text(session->error, sizeof(session->error),
                  "buffer_upload: vkMapMemory failed");
        return -1;
    }
    memcpy(mapped, data, (size_t)size);
    f_um(dev, buffer->memory);
    return NAR_VULKAN_BUFFER_RESULT_OK;
}

uint64_t nar_vulkan_buffer_size(const nar_vulkan_buffer *buffer)
{
    return buffer == NULL ? 0 : buffer->size;
}

/* ========================================================================= */

int32_t nar_vulkan_offscreen_readback(
        nar_vulkan_session *session,
        nar_vulkan_offscreen_image *image,
        void *dst, uint64_t capacity)
{
    if (session == NULL || image == NULL || dst == NULL) return -1;
    if (capacity < image->readback_size) {
        copy_text(session->error, sizeof(session->error),
                  "offscreen readback: destination buffer too small");
        return -1;
    }
    if (!image->fence_submitted) {
        /* Nothing was submitted; just zero the output. */
        memset(dst, 0, (size_t)image->readback_size);
        return NAR_OFFSCREEN_RESULT_OK;
    }

    nar_vk_device dev  = image->device;
    nar_vk_get_device_proc_addr_fn gdpa = image->gdpa;

    nar_vk_wait_for_fences_fn f_wff = (nar_vk_wait_for_fences_fn)dp(gdpa, dev, "vkWaitForFences");
    nar_vk_map_memory_fn      f_mm  = (nar_vk_map_memory_fn)     dp(gdpa, dev, "vkMapMemory");
    nar_vk_unmap_memory_fn    f_um  = (nar_vk_unmap_memory_fn)   dp(gdpa, dev, "vkUnmapMemory");

    if (!f_wff || !f_mm || !f_um) {
        copy_text(session->error, sizeof(session->error),
                  "offscreen readback: required entry points unavailable");
        return -1;
    }

    /* Wait for the submitted work to finish. */
    nar_vk_result wr = f_wff(dev, 1, &image->fence, 1 /* waitAll */, UINT64_MAX);
    if (wr != NAR_VK_SUCCESS) {
        copy_text(session->error, sizeof(session->error),
                  "offscreen readback: vkWaitForFences failed");
        return -1;
    }
    image->fence_submitted = 0;

    /* Map, copy, unmap. */
    void *mapped = NULL;
    if (f_mm(dev, image->readback_memory, 0, image->readback_size, 0, &mapped) != NAR_VK_SUCCESS
        || mapped == NULL) {
        copy_text(session->error, sizeof(session->error),
                  "offscreen readback: vkMapMemory failed");
        return -1;
    }
    memcpy(dst, mapped, (size_t)image->readback_size);
    f_um(dev, image->readback_memory);
    return NAR_OFFSCREEN_RESULT_OK;
}

/* =========================================================================
 * Shader module, graphics pipeline, and command recording ABI.
 * All Vulkan declarations use the same runtime-resolved idiom as the rest of
 * this file.  No Vulkan SDK is required at build time.
 * ========================================================================= */

typedef void *nar_vk_shader_module;
typedef void *nar_vk_pipeline;
typedef void *nar_vk_pipeline_layout;
typedef void *nar_vk_descriptor_set_layout;

typedef struct nar_vk_shader_module_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    size_t code_size;           /* in bytes */
    const uint32_t *code;
} nar_vk_shader_module_create_info;

typedef struct nar_vk_pipeline_layout_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    uint32_t set_layout_count;
    const nar_vk_descriptor_set_layout *set_layouts;
    uint32_t push_constant_range_count;
    const void *push_constant_ranges;
} nar_vk_pipeline_layout_create_info;

typedef struct nar_vk_pipeline_shader_stage_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    uint32_t stage;        /* VK_SHADER_STAGE_VERTEX_BIT=1, FRAGMENT_BIT=16 */
    nar_vk_shader_module module;
    const char *name;      /* entry point */
    const void *specialization_info;
} nar_vk_pipeline_shader_stage_create_info;

typedef struct nar_vk_vertex_input_binding_description {
    uint32_t binding;
    uint32_t stride;
    uint32_t input_rate;    /* 0 = VK_VERTEX_INPUT_RATE_VERTEX */
} nar_vk_vertex_input_binding_description;

typedef struct nar_vk_vertex_input_attribute_description {
    uint32_t location;
    uint32_t binding;
    uint32_t format;    /* VK_FORMAT_R32G32_SFLOAT=103, VK_FORMAT_R32G32B32_SFLOAT=106 */
    uint32_t offset;
} nar_vk_vertex_input_attribute_description;

typedef struct nar_vk_pipeline_vertex_input_state_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    uint32_t vertex_binding_description_count;
    const nar_vk_vertex_input_binding_description *vertex_binding_descriptions;
    uint32_t vertex_attribute_description_count;
    const nar_vk_vertex_input_attribute_description *vertex_attribute_descriptions;
} nar_vk_pipeline_vertex_input_state_create_info;

typedef struct nar_vk_pipeline_input_assembly_state_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    uint32_t topology;          /* 3 = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST */
    uint32_t primitive_restart_enable;
} nar_vk_pipeline_input_assembly_state_create_info;

typedef struct nar_vk_viewport { float x, y, width, height, min_depth, max_depth; } nar_vk_viewport;

typedef struct nar_vk_pipeline_viewport_state_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    uint32_t viewport_count;
    const nar_vk_viewport *viewports;
    uint32_t scissor_count;
    const nar_vk_rect2d *scissors;
} nar_vk_pipeline_viewport_state_create_info;

typedef struct nar_vk_pipeline_rasterization_state_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    uint32_t depth_clamp_enable;
    uint32_t rasterizer_discard_enable;
    uint32_t polygon_mode;          /* 0 = VK_POLYGON_MODE_FILL */
    uint32_t cull_mode;             /* 0 = VK_CULL_MODE_NONE */
    uint32_t front_face;            /* 0 = VK_FRONT_FACE_COUNTER_CLOCKWISE */
    uint32_t depth_bias_enable;
    float depth_bias_constant_factor;
    float depth_bias_clamp;
    float depth_bias_slope_factor;
    float line_width;
} nar_vk_pipeline_rasterization_state_create_info;

typedef struct nar_vk_pipeline_multisample_state_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    uint32_t rasterization_samples; /* 1 = VK_SAMPLE_COUNT_1_BIT */
    uint32_t sample_shading_enable;
    float min_sample_shading;
    const uint32_t *sample_mask;
    uint32_t alpha_to_coverage_enable;
    uint32_t alpha_to_one_enable;
} nar_vk_pipeline_multisample_state_create_info;

typedef struct nar_vk_pipeline_color_blend_attachment_state {
    uint32_t blend_enable;
    uint32_t src_color_blend_factor;
    uint32_t dst_color_blend_factor;
    uint32_t color_blend_op;
    uint32_t src_alpha_blend_factor;
    uint32_t dst_alpha_blend_factor;
    uint32_t alpha_blend_op;
    uint32_t color_write_mask;  /* 0xF = RGBA */
} nar_vk_pipeline_color_blend_attachment_state;

typedef struct nar_vk_pipeline_color_blend_state_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    uint32_t logic_op_enable;
    uint32_t logic_op;
    uint32_t attachment_count;
    const nar_vk_pipeline_color_blend_attachment_state *attachments;
    float blend_constants[4];
} nar_vk_pipeline_color_blend_state_create_info;

typedef struct nar_vk_graphics_pipeline_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    uint32_t stage_count;
    const nar_vk_pipeline_shader_stage_create_info *stages;
    const nar_vk_pipeline_vertex_input_state_create_info *vertex_input_state;
    const nar_vk_pipeline_input_assembly_state_create_info *input_assembly_state;
    const void *tessellation_state;
    const nar_vk_pipeline_viewport_state_create_info *viewport_state;
    const nar_vk_pipeline_rasterization_state_create_info *rasterization_state;
    const nar_vk_pipeline_multisample_state_create_info *multisample_state;
    const void *depth_stencil_state;
    const nar_vk_pipeline_color_blend_state_create_info *color_blend_state;
    const void *dynamic_state;
    nar_vk_pipeline_layout layout;
    nar_vk_render_pass render_pass;
    uint32_t subpass;
    nar_vk_pipeline base_pipeline_handle;
    int32_t base_pipeline_index;
} nar_vk_graphics_pipeline_create_info;

typedef nar_vk_result (*nar_vk_create_shader_module_fn)(
    nar_vk_device, const nar_vk_shader_module_create_info *, const void *, nar_vk_shader_module *);
typedef void (*nar_vk_destroy_shader_module_fn)(nar_vk_device, nar_vk_shader_module, const void *);
typedef nar_vk_result (*nar_vk_create_pipeline_layout_fn)(
    nar_vk_device, const nar_vk_pipeline_layout_create_info *, const void *, nar_vk_pipeline_layout *);
typedef void (*nar_vk_destroy_pipeline_layout_fn)(nar_vk_device, nar_vk_pipeline_layout, const void *);
typedef nar_vk_result (*nar_vk_create_graphics_pipelines_fn)(
    nar_vk_device, void * /*cache*/, uint32_t, const nar_vk_graphics_pipeline_create_info *,
    const void *, nar_vk_pipeline *);
typedef void (*nar_vk_destroy_pipeline_fn)(nar_vk_device, nar_vk_pipeline, const void *);
typedef void (*nar_vk_cmd_bind_pipeline_fn)(nar_vk_command_buffer, uint32_t, nar_vk_pipeline);
typedef void (*nar_vk_cmd_bind_vertex_buffers_fn)(
    nar_vk_command_buffer, uint32_t first, uint32_t count,
    const nar_vk_buffer *, const uint64_t *offsets);
typedef void (*nar_vk_cmd_draw_fn)(
    nar_vk_command_buffer, uint32_t vertex_count, uint32_t instance_count,
    uint32_t first_vertex, uint32_t first_instance);
typedef void (*nar_vk_cmd_set_viewport_fn)(
    nar_vk_command_buffer, uint32_t first, uint32_t count, const nar_vk_viewport *);
typedef void (*nar_vk_cmd_set_scissor_fn)(
    nar_vk_command_buffer, uint32_t first, uint32_t count, const nar_vk_rect2d *);

enum {
    NAR_VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO             = 16,
    NAR_VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO           = 30,
    NAR_VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO         = 28,
    NAR_VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO     = 18,
    NAR_VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO = 19,
    NAR_VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO = 20,
    NAR_VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO   = 22,
    NAR_VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO = 23,
    NAR_VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO = 24,
    NAR_VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO = 26,
    NAR_VK_SHADER_STAGE_VERTEX_BIT   = 0x00000001,
    NAR_VK_SHADER_STAGE_FRAGMENT_BIT = 0x00000010,
    NAR_VK_FORMAT_R32G32_SFLOAT      = 103,
    NAR_VK_FORMAT_R32G32B32_SFLOAT   = 106,
    NAR_VK_PIPELINE_BIND_POINT_GRAPHICS_VALUE = 0,
    NAR_VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST = 3,
    NAR_VK_DYNAMIC_STATE_VIEWPORT = 0,
    NAR_VK_DYNAMIC_STATE_SCISSOR  = 1
};

/* ---- opaque structs ---- */
struct nar_vulkan_shader_module {
    nar_vk_shader_module module;
    nar_vk_device device;
    nar_vk_get_device_proc_addr_fn gdpa;
};

struct nar_vulkan_pipeline {
    nar_vk_pipeline pipeline;
    nar_vk_pipeline_layout layout;
    nar_vk_device device;
    nar_vk_get_device_proc_addr_fn gdpa;
};

/* ---- shader module ---- */
nar_vulkan_shader_module *nar_vulkan_shader_module_create(
        nar_vulkan_session *session, const void *code, uint64_t code_size)
{
    if (session == NULL || code == NULL || code_size == 0 || (code_size % 4) != 0) return NULL;
    nar_vk_device dev = session->device;
    nar_vk_get_device_proc_addr_fn gdpa = session->gdpa;

    nar_vk_create_shader_module_fn f_csm =
        (nar_vk_create_shader_module_fn)dp(gdpa, dev, "vkCreateShaderModule");
    if (!f_csm) {
        copy_text(session->error, sizeof(session->error),
                  "shader_module_create: vkCreateShaderModule unavailable");
        return NULL;
    }
    nar_vk_shader_module_create_info ci;
    memset(&ci, 0, sizeof(ci));
    ci.s_type    = NAR_VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ci.code_size = (size_t)code_size;
    ci.code      = (const uint32_t *)code;
    nar_vk_shader_module handle = NULL;
    if (f_csm(dev, &ci, NULL, &handle) != NAR_VK_SUCCESS || handle == NULL) {
        copy_text(session->error, sizeof(session->error),
                  "shader_module_create: vkCreateShaderModule failed");
        return NULL;
    }
    struct nar_vulkan_shader_module *sm = calloc(1, sizeof(*sm));
    if (sm == NULL) {
        nar_vk_destroy_shader_module_fn f_dsm =
            (nar_vk_destroy_shader_module_fn)dp(gdpa, dev, "vkDestroyShaderModule");
        if (f_dsm) f_dsm(dev, handle, NULL);
        copy_text(session->error, sizeof(session->error),
                  "shader_module_create: out of memory");
        return NULL;
    }
    sm->module = handle;
    sm->device = dev;
    sm->gdpa   = gdpa;
    return sm;
}

void nar_vulkan_shader_module_destroy(
        nar_vulkan_session *session, nar_vulkan_shader_module *module)
{
    if (module == NULL) return;
    nar_vk_destroy_shader_module_fn f_dsm =
        (nar_vk_destroy_shader_module_fn)dp(module->gdpa, module->device, "vkDestroyShaderModule");
    if (f_dsm && module->module) f_dsm(module->device, module->module, NULL);
    free(module);
    (void)session;
}

/* ---- pipeline ---- */

/*
 * Dynamic-state info helper.  Viewport and scissor are set dynamically so the
 * pipeline is independent of the exact offscreen image dimensions.
 */
typedef struct nar_vk_pipeline_dynamic_state_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    uint32_t dynamic_state_count;
    const uint32_t *dynamic_states;
} nar_vk_pipeline_dynamic_state_create_info;

enum { NAR_VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO = 27 };

nar_vulkan_pipeline *nar_vulkan_pipeline_create(
        nar_vulkan_session *session,
        nar_vulkan_offscreen_image *image,
        nar_vulkan_shader_module *vertex_shader,
        nar_vulkan_shader_module *fragment_shader)
{
    if (session == NULL || image == NULL || vertex_shader == NULL || fragment_shader == NULL)
        return NULL;
    nar_vk_device dev = session->device;
    nar_vk_get_device_proc_addr_fn gdpa = session->gdpa;

    nar_vk_create_pipeline_layout_fn f_cpl =
        (nar_vk_create_pipeline_layout_fn)dp(gdpa, dev, "vkCreatePipelineLayout");
    nar_vk_destroy_pipeline_layout_fn f_dpl =
        (nar_vk_destroy_pipeline_layout_fn)dp(gdpa, dev, "vkDestroyPipelineLayout");
    nar_vk_create_graphics_pipelines_fn f_cgp =
        (nar_vk_create_graphics_pipelines_fn)dp(gdpa, dev, "vkCreateGraphicsPipelines");
    if (!f_cpl || !f_dpl || !f_cgp) {
        copy_text(session->error, sizeof(session->error),
                  "pipeline_create: required entry points unavailable");
        return NULL;
    }

    /* Empty pipeline layout — no descriptor sets, no push constants. */
    nar_vk_pipeline_layout_create_info layout_ci;
    memset(&layout_ci, 0, sizeof(layout_ci));
    layout_ci.s_type = NAR_VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    nar_vk_pipeline_layout layout = NULL;
    if (f_cpl(dev, &layout_ci, NULL, &layout) != NAR_VK_SUCCESS || layout == NULL) {
        copy_text(session->error, sizeof(session->error),
                  "pipeline_create: vkCreatePipelineLayout failed");
        return NULL;
    }

    /* Fixed vertex layout: binding 0, stride 20, vertex-rate.
     * location 0: vec2 position (R32G32_SFLOAT) at offset 0
     * location 1: vec3 color    (R32G32B32_SFLOAT) at offset 8  */
    nar_vk_vertex_input_binding_description binding;
    binding.binding    = 0;
    binding.stride     = 20;
    binding.input_rate = 0; /* VERTEX */

    nar_vk_vertex_input_attribute_description attribs[2];
    attribs[0].location = 0;
    attribs[0].binding  = 0;
    attribs[0].format   = NAR_VK_FORMAT_R32G32_SFLOAT;
    attribs[0].offset   = 0;
    attribs[1].location = 1;
    attribs[1].binding  = 0;
    attribs[1].format   = NAR_VK_FORMAT_R32G32B32_SFLOAT;
    attribs[1].offset   = 8;

    nar_vk_pipeline_vertex_input_state_create_info vi;
    memset(&vi, 0, sizeof(vi));
    vi.s_type                           = NAR_VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    vi.vertex_binding_description_count  = 1;
    vi.vertex_binding_descriptions       = &binding;
    vi.vertex_attribute_description_count= 2;
    vi.vertex_attribute_descriptions     = attribs;

    nar_vk_pipeline_input_assembly_state_create_info ia;
    memset(&ia, 0, sizeof(ia));
    ia.s_type   = NAR_VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    ia.topology = NAR_VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    /* Viewport and scissor are dynamic; placeholder counts required by spec. */
    nar_vk_pipeline_viewport_state_create_info vp;
    memset(&vp, 0, sizeof(vp));
    vp.s_type         = NAR_VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    vp.viewport_count = 1;
    vp.scissor_count  = 1;

    nar_vk_pipeline_rasterization_state_create_info rs;
    memset(&rs, 0, sizeof(rs));
    rs.s_type      = NAR_VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    rs.polygon_mode= 0; /* FILL */
    rs.cull_mode   = 0; /* NONE */
    rs.front_face  = 0; /* COUNTER_CLOCKWISE */
    rs.line_width  = 1.0f;

    nar_vk_pipeline_multisample_state_create_info ms;
    memset(&ms, 0, sizeof(ms));
    ms.s_type                  = NAR_VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    ms.rasterization_samples   = NAR_VK_SAMPLE_COUNT_1_BIT;
    ms.min_sample_shading      = 1.0f;

    nar_vk_pipeline_color_blend_attachment_state cba;
    memset(&cba, 0, sizeof(cba));
    cba.blend_enable    = 0;
    cba.color_write_mask= 0xF; /* RGBA */

    nar_vk_pipeline_color_blend_state_create_info cb;
    memset(&cb, 0, sizeof(cb));
    cb.s_type          = NAR_VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    cb.attachment_count= 1;
    cb.attachments     = &cba;

    uint32_t dynamic_states[2] = { NAR_VK_DYNAMIC_STATE_VIEWPORT, NAR_VK_DYNAMIC_STATE_SCISSOR };
    nar_vk_pipeline_dynamic_state_create_info ds;
    memset(&ds, 0, sizeof(ds));
    ds.s_type               = NAR_VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    ds.dynamic_state_count  = 2;
    ds.dynamic_states       = dynamic_states;

    nar_vk_pipeline_shader_stage_create_info stages[2];
    memset(stages, 0, sizeof(stages));
    stages[0].s_type  = NAR_VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage   = NAR_VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module  = vertex_shader->module;
    stages[0].name    = "main";
    stages[1].s_type  = NAR_VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage   = NAR_VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module  = fragment_shader->module;
    stages[1].name    = "main";

    nar_vk_graphics_pipeline_create_info gpci;
    memset(&gpci, 0, sizeof(gpci));
    gpci.s_type                = 28; /* VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO */
    gpci.stage_count           = 2;
    gpci.stages                = stages;
    gpci.vertex_input_state    = &vi;
    gpci.input_assembly_state  = &ia;
    gpci.viewport_state        = &vp;
    gpci.rasterization_state   = &rs;
    gpci.multisample_state     = &ms;
    gpci.color_blend_state     = &cb;
    gpci.dynamic_state         = &ds;
    gpci.layout                = layout;
    gpci.render_pass           = image->render_pass;
    gpci.subpass               = 0;
    gpci.base_pipeline_index   = -1;

    nar_vk_pipeline pipeline = NULL;
    if (f_cgp(dev, NULL, 1, &gpci, NULL, &pipeline) != NAR_VK_SUCCESS || pipeline == NULL) {
        f_dpl(dev, layout, NULL);
        copy_text(session->error, sizeof(session->error),
                  "pipeline_create: vkCreateGraphicsPipelines failed");
        return NULL;
    }

    struct nar_vulkan_pipeline *pl = calloc(1, sizeof(*pl));
    if (pl == NULL) {
        nar_vk_destroy_pipeline_fn f_dp =
            (nar_vk_destroy_pipeline_fn)dp(gdpa, dev, "vkDestroyPipeline");
        if (f_dp) f_dp(dev, pipeline, NULL);
        f_dpl(dev, layout, NULL);
        copy_text(session->error, sizeof(session->error),
                  "pipeline_create: out of memory");
        return NULL;
    }
    pl->pipeline = pipeline;
    pl->layout   = layout;
    pl->device   = dev;
    pl->gdpa     = gdpa;
    return pl;
}

void nar_vulkan_pipeline_destroy(
        nar_vulkan_session *session, nar_vulkan_pipeline *pipeline)
{
    if (pipeline == NULL) return;
    nar_vk_destroy_pipeline_fn f_dp =
        (nar_vk_destroy_pipeline_fn)dp(pipeline->gdpa, pipeline->device, "vkDestroyPipeline");
    nar_vk_destroy_pipeline_layout_fn f_dpl =
        (nar_vk_destroy_pipeline_layout_fn)dp(pipeline->gdpa, pipeline->device, "vkDestroyPipelineLayout");
    if (f_dp && pipeline->pipeline) f_dp(pipeline->device, pipeline->pipeline, NULL);
    if (f_dpl && pipeline->layout)  f_dpl(pipeline->device, pipeline->layout, NULL);
    free(pipeline);
    (void)session;
}

/* ---- command recording ---- */

int32_t nar_vulkan_cmd_bind_pipeline(
        nar_vulkan_session *session,
        void *command_buffer,
        nar_vulkan_pipeline *pipeline)
{
    if (session == NULL || command_buffer == NULL || pipeline == NULL) return -1;
    nar_vk_cmd_bind_pipeline_fn f =
        (nar_vk_cmd_bind_pipeline_fn)dp(session->gdpa, session->device, "vkCmdBindPipeline");
    if (!f) {
        copy_text(session->error, sizeof(session->error),
                  "cmd_bind_pipeline: vkCmdBindPipeline unavailable");
        return -1;
    }
    f((nar_vk_command_buffer)command_buffer, NAR_VK_PIPELINE_BIND_POINT_GRAPHICS_VALUE,
      pipeline->pipeline);
    return NAR_VULKAN_PIPELINE_RESULT_OK;
}

int32_t nar_vulkan_cmd_bind_vertex_buffer(
        nar_vulkan_session *session,
        void *command_buffer,
        nar_vulkan_buffer *buffer, uint64_t offset)
{
    if (session == NULL || command_buffer == NULL || buffer == NULL) return -1;
    nar_vk_cmd_bind_vertex_buffers_fn f =
        (nar_vk_cmd_bind_vertex_buffers_fn)dp(session->gdpa, session->device, "vkCmdBindVertexBuffers");
    if (!f) {
        copy_text(session->error, sizeof(session->error),
                  "cmd_bind_vertex_buffer: vkCmdBindVertexBuffers unavailable");
        return -1;
    }
    f((nar_vk_command_buffer)command_buffer, 0, 1, &buffer->buffer, &offset);
    return NAR_VULKAN_PIPELINE_RESULT_OK;
}

int32_t nar_vulkan_cmd_draw(
        nar_vulkan_session *session,
        void *command_buffer,
        uint32_t vertex_count, uint32_t first_vertex)
{
    if (session == NULL || command_buffer == NULL || vertex_count == 0) return -1;
    nar_vk_device dev = session->device;
    nar_vk_get_device_proc_addr_fn gdpa = session->gdpa;

    nar_vk_cmd_set_viewport_fn f_sv =
        (nar_vk_cmd_set_viewport_fn)dp(gdpa, dev, "vkCmdSetViewport");
    nar_vk_cmd_set_scissor_fn f_ss =
        (nar_vk_cmd_set_scissor_fn)dp(gdpa, dev, "vkCmdSetScissor");
    nar_vk_cmd_draw_fn f_d =
        (nar_vk_cmd_draw_fn)dp(gdpa, dev, "vkCmdDraw");

    if (!f_sv || !f_ss || !f_d) {
        copy_text(session->error, sizeof(session->error),
                  "cmd_draw: required draw entry points unavailable");
        return -1;
    }

    /* Set viewport and scissor to the session dimensions. The pipeline uses
     * dynamic state so these must be recorded before every draw call. */
    nar_vk_viewport viewport;
    viewport.x         = 0.0f;
    viewport.y         = 0.0f;
    viewport.width     = (float)session->width;
    viewport.height    = (float)session->height;
    viewport.min_depth = 0.0f;
    viewport.max_depth = 1.0f;

    nar_vk_rect2d scissor;
    scissor.x      = 0;
    scissor.y      = 0;
    scissor.width  = session->width;
    scissor.height = session->height;

    nar_vk_command_buffer cb = (nar_vk_command_buffer)command_buffer;
    f_sv(cb, 0, 1, &viewport);
    f_ss(cb, 0, 1, &scissor);
    f_d(cb, vertex_count, 1, first_vertex, 0);
    return NAR_VULKAN_PIPELINE_RESULT_OK;
}
