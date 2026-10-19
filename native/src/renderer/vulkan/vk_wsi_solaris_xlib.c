#include "native_accelerator_renderer.h"

#include <stdint.h>
#include <stdio.h>
#include <string.h>

typedef int32_t nar_vk_result;
typedef void *nar_vk_instance;
typedef void *nar_vk_surface;
typedef void *(*nar_vk_get_instance_proc_addr_fn)(nar_vk_instance, const char *);
typedef struct nar_vk_xlib_surface_create_info {
    uint32_t s_type;
    const void *p_next;
    uint32_t flags;
    void *dpy;
    uint64_t window;
} nar_vk_xlib_surface_create_info;
typedef nar_vk_result (*nar_vk_create_xlib_surface_fn)(nar_vk_instance,
        const nar_vk_xlib_surface_create_info *, const void *, nar_vk_surface *);

/* VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR. Kept local so the optional
 * renderer does not need an installed Vulkan SDK or Xlib development headers. */
#define NAR_VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR 1000006000
#define NAR_VK_SUCCESS 0

int nar_vulkan_wsi_create_platform_surface(
        uint32_t platform,
        nar_vk_get_instance_proc_addr_fn gipa,
        nar_vk_instance instance,
        const nar_vulkan_wsi_window *window,
        nar_vk_surface *out_surface,
        char *error,
        size_t error_capacity)
{
    if (out_surface != NULL) *out_surface = NULL;
    if (error != NULL && error_capacity > 0) error[0] = '\0';
    if (platform != NAR_VULKAN_WSI_PLATFORM_XLIB) {
        if (error != NULL && error_capacity > 0)
            snprintf(error, error_capacity, "WSI platform %u is not implemented by this build", platform);
        return -1;
    }
    if (gipa == NULL || instance == NULL || window == NULL ||
            window->native_display == 0 || window->native_window == 0 || out_surface == NULL) {
        if (error != NULL && error_capacity > 0)
            snprintf(error, error_capacity, "Xlib WSI requires a display and non-zero window handle");
        return -1;
    }
    nar_vk_create_xlib_surface_fn create_surface =
            (nar_vk_create_xlib_surface_fn)gipa(instance, "vkCreateXlibSurfaceKHR");
    if (create_surface == NULL) {
        if (error != NULL && error_capacity > 0)
            snprintf(error, error_capacity, "vkCreateXlibSurfaceKHR is unavailable");
        return -1;
    }
    nar_vk_xlib_surface_create_info info;
    memset(&info, 0, sizeof(info));
    info.s_type = NAR_VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR;
    info.dpy = (void *)(uintptr_t)window->native_display;
    info.window = window->native_window;
    nar_vk_result result = create_surface(instance, &info, NULL, out_surface);
    if (result != NAR_VK_SUCCESS || *out_surface == NULL) {
        if (error != NULL && error_capacity > 0)
            snprintf(error, error_capacity, "vkCreateXlibSurfaceKHR failed (VkResult %d)", result);
        return result == 0 ? -1 : result;
    }
    return 0;
}
