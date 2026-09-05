#include "native_accelerator_renderer.h"

#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#include <windows.h>
typedef HMODULE nar_library_handle;
static nar_library_handle nar_open_library(const char *name) { return LoadLibraryA(name); }
static void *nar_find_symbol(nar_library_handle h, const char *name) { return (void *)GetProcAddress(h, name); }
#else
#include <dlfcn.h>
typedef void *nar_library_handle;
static nar_library_handle nar_open_library(const char *name) { return dlopen(name, RTLD_NOW | RTLD_LOCAL); }
static void *nar_find_symbol(nar_library_handle h, const char *name) { return dlsym(h, name); }
#endif

typedef int32_t (*nar_vk_enumerate_instance_version_fn)(uint32_t *version);
/* Minimal core Vulkan ABI declarations from the public API specification. */
typedef void *nar_vk_instance;
typedef void *nar_vk_physical_device;
typedef int32_t nar_vk_result;

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

typedef nar_vk_result (*nar_vk_create_instance_fn)(const nar_vk_instance_create_info *, const void *, nar_vk_instance *);
typedef void (*nar_vk_destroy_instance_fn)(nar_vk_instance, const void *);
typedef nar_vk_result (*nar_vk_enumerate_physical_devices_fn)(nar_vk_instance, uint32_t *, nar_vk_physical_device *);


typedef struct nar_vulkan_loader_state {
    int initialized;
    nar_library_handle handle;
    char name[256];
    uint32_t api_version;
} nar_vulkan_loader_state;

static nar_vulkan_loader_state g_loader;

static void nar_probe_loader(void) {
    if (g_loader.initialized) return;
    g_loader.initialized = 1;

    const char *override_name = getenv("NATIVE_ACCELERATOR_VULKAN_LIBRARY");
    const char *candidates[8];
    size_t count = 0;
    if (override_name != NULL && override_name[0] != '\0') candidates[count++] = override_name;
#if defined(_WIN32)
    candidates[count++] = "vulkan-1.dll";
#elif defined(__APPLE__)
    candidates[count++] = "libvulkan.1.dylib";
    candidates[count++] = "libvulkan.dylib";
    candidates[count++] = "libMoltenVK.dylib";
#else
    candidates[count++] = "libvulkan.so.1";
    candidates[count++] = "libvulkan.so";
#endif

    for (size_t i = 0; i < count; ++i) {
        nar_library_handle h = nar_open_library(candidates[i]);
        if (h == 0) continue;
        if (nar_find_symbol(h, "vkGetInstanceProcAddr") == NULL) continue;
        g_loader.handle = h;
        strncpy(g_loader.name, candidates[i], sizeof(g_loader.name) - 1u);
        g_loader.name[sizeof(g_loader.name) - 1u] = '\0';
        break;
    }

    if (g_loader.handle != 0) {
        nar_vk_enumerate_instance_version_fn enumerate_version =
            (nar_vk_enumerate_instance_version_fn)nar_find_symbol(g_loader.handle, "vkEnumerateInstanceVersion");
        if (enumerate_version != NULL) {
            uint32_t version = 0;
            if (enumerate_version(&version) == 0) g_loader.api_version = version;
        }
        if (g_loader.api_version == 0) g_loader.api_version = UINT32_C(1) << 22; /* Vulkan 1.0 */
    }
}

int32_t nar_vulkan_loader_available(void) {
    nar_probe_loader();
    return g_loader.handle != 0 ? 1 : 0;
}

uint32_t nar_vulkan_loader_api_version(void) {
    nar_probe_loader();
    return g_loader.api_version;
}

uint64_t nar_vulkan_loader_name(char *dst, uint64_t capacity) {
    nar_probe_loader();
    const char *name = g_loader.handle != 0 ? g_loader.name : "unavailable";
    uint64_t length = (uint64_t)strlen(name);
    if (dst != NULL && capacity > 0) {
        uint64_t copy = length < capacity - 1 ? length : capacity - 1;
        memcpy(dst, name, (size_t)copy);
        dst[copy] = '\0';
    }
    return length;
}

uint32_t nar_vulkan_physical_device_count(void) {
    nar_probe_loader();
    if (g_loader.handle == 0) return 0;

    nar_vk_create_instance_fn create_instance =
        (nar_vk_create_instance_fn)nar_find_symbol(g_loader.handle, "vkCreateInstance");
    nar_vk_destroy_instance_fn destroy_instance =
        (nar_vk_destroy_instance_fn)nar_find_symbol(g_loader.handle, "vkDestroyInstance");
    nar_vk_enumerate_physical_devices_fn enumerate_devices =
        (nar_vk_enumerate_physical_devices_fn)nar_find_symbol(g_loader.handle, "vkEnumeratePhysicalDevices");
    if (create_instance == NULL || destroy_instance == NULL || enumerate_devices == NULL) return 0;

    nar_vk_application_info app;
    memset(&app, 0, sizeof(app));
    app.s_type = 0; /* VK_STRUCTURE_TYPE_APPLICATION_INFO */
    app.application_name = "Native Accelerator";
    app.application_version = 1;
    app.engine_name = "Native Accelerator Renderer";
    app.engine_version = 1;
    app.api_version = g_loader.api_version;

    nar_vk_instance_create_info create;
    memset(&create, 0, sizeof(create));
    create.s_type = 1; /* VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO */
    create.application_info = &app;

    nar_vk_instance instance = NULL;
    if (create_instance(&create, NULL, &instance) != 0 || instance == NULL) return 0;
    uint32_t count = 0;
    if (enumerate_devices(instance, &count, NULL) != 0) count = 0;
    destroy_instance(instance, NULL);
    return count;
}
