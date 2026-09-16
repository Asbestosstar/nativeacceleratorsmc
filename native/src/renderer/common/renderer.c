#include "native_accelerator_renderer.h"

#include <stdio.h>
#include <string.h>

uint32_t nar_abi_version(void) {
    return NAR_ABI_VERSION;
}

uint64_t nar_capabilities(void) {
    uint64_t caps = NAR_CAP_SCENE_DATABASE |
                    NAR_CAP_ARENA_ALLOCATOR |
                    NAR_CAP_CPU_FRUSTUM_CULL |
                    NAR_CAP_INDIRECT_COMMANDS |
                    NAR_CAP_VOXEL_FACE_MASKS |
                    NAR_CAP_PIPELINE_CACHE_IO;
#if NAR_NATIVE_WORKERS
    caps |= NAR_CAP_NATIVE_WORKERS;
#endif
    if (nar_renderer_platform_role() == NAR_RENDERER_ROLE_CLIENT) {
        caps |= NAR_CAP_CLIENT_RENDERER;
    } else {
        caps |= NAR_CAP_SERVER_ONLY_HOST;
    }
    if (nar_vulkan_loader_available()) caps |= NAR_CAP_VULKAN_LOADER;
    /* Device evidence is taken from the policy, which only probes when the answer can matter. Calling
     * nar_vulkan_physical_device_count() here would force a Vulkan instance creation at capability
     * query time, which aborts the process on some loader builds. */
    if ((nar_renderer_platform_evidence() & NAR_RENDERER_EVIDENCE_VULKAN_DEVICE) != 0) {
        caps |= NAR_CAP_VULKAN_DEVICE;
    }
    return caps;
}

uint64_t nar_backend_name(char *dst, uint64_t capacity) {
    char loader[192];
    char policy[256];
    nar_vulkan_loader_name(loader, sizeof(loader));
    nar_renderer_platform_name(policy, sizeof(policy));
    char text[512];
    if (nar_vulkan_loader_available()) {
        (void)snprintf(text, sizeof(text), "native-scene+vulkan-loader:%s [%s]", loader, policy);
    } else {
        (void)snprintf(text, sizeof(text), "native-scene+vulkan-unavailable [%s]", policy);
    }
    uint64_t length = (uint64_t)strlen(text);
    if (dst != NULL && capacity > 0) {
        uint64_t copy = length < capacity - 1 ? length : capacity - 1;
        memcpy(dst, text, (size_t)copy);
        dst[copy] = 0;
    }
    return length;
}
