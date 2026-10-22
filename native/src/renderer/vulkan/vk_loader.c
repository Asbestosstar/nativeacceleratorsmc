#include "native_accelerator_renderer.h"

#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#if defined(_WIN32)
#include <windows.h>
typedef HMODULE nar_library_handle;
static nar_library_handle nar_open_library(const char *name) { return LoadLibraryA(name); }
static void *nar_find_symbol(nar_library_handle h, const char *name) { return (void *)GetProcAddress(h, name); }
static void nar_close_library(nar_library_handle h) { if (h != NULL) FreeLibrary(h); }
#else
#include <dlfcn.h>
typedef void *nar_library_handle;
static nar_library_handle nar_open_library(const char *name) { return dlopen(name, RTLD_NOW | RTLD_LOCAL); }
static void *nar_find_symbol(nar_library_handle h, const char *name) { return dlsym(h, name); }
static void nar_close_library(nar_library_handle h) { if (h != NULL) dlclose(h); }
#endif

#if !defined(_WIN32)
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>
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

/*
 * MoltenVK configuration defaults.
 *
 * MoltenVK advertises and uses Metal argument buffers (a Metal 2 feature) by default. On some Metal
 * drivers -- notably older/OCLP-patched stacks -- the Apple Metal driver does not provide indirect
 * argument encoders and aborts inside
 * -[MTLIOAccelDevice newIndirectArgumentEncoderWithLayout:] while MoltenVK is creating its devices.
 * abort() cannot be caught, so the whole process dies even though Vulkan would otherwise work.
 *
 * Disabling MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS makes MoltenVK avoid that code path; on the
 * affected machines vkCreateInstance then succeeds and enumerates the physical devices normally.
 * The setting is only a default: an explicit value from the environment (or the layer-settings
 * file) always wins. The environment is read lazily by MoltenVK, so setting it here -- after
 * dlopen() but before the first Vulkan call -- takes effect.
 */
static void nar_apply_moltenvk_defaults(void) {
#if defined(__APPLE__)
    if (getenv("MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS") == NULL) {
        setenv("MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS", "0", 0);
    }
#endif
}

/* Add an explicit loader path without relying on the process' global library search path.
 * Solaris launchers commonly provide VULKAN_LIBRARY_PATH while Minecraft also exposes the
 * exact file as MC_VULKAN_LIBRARY_NAME.  dlopen() does not interpret the former as a search
 * path, so the renderer must turn directory entries into absolute candidates itself. */
static void nar_add_loader_candidate(const char **candidates, size_t *count, size_t capacity,
                                     char storage[][1024], size_t *storage_count,
                                     const char *value) {
    if (candidates == NULL || count == NULL || storage == NULL || storage_count == NULL
            || value == NULL || value[0] == '\0') return;
    if (*count >= capacity || *storage_count >= 32u) return;
    size_t length = strlen(value);
    if (length >= 1024u) return;
    memcpy(storage[*storage_count], value, length + 1u);
    candidates[(*count)++] = storage[(*storage_count)++];
}

static void nar_add_loader_directory(const char **candidates, size_t *count, size_t capacity,
                                     char storage[][1024], size_t *storage_count,
                                     const char *directory) {
    if (directory == NULL || directory[0] == '\0') return;
    static const char *names[] = {
        "libvulkan.so.1", "libvulkan.so", "libvulkan.sl", "libvulkan.sl.1", "libvulkan.a"
    };
    char path[1024];
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); ++i) {
        int written = snprintf(path, sizeof(path), "%s/%s", directory, names[i]);
        if (written <= 0 || (size_t)written >= sizeof(path)) continue;
        nar_add_loader_candidate(candidates, count, capacity, storage, storage_count, path);
    }
}

static void nar_probe_loader(void) {
    if (g_loader.initialized) return;
    g_loader.initialized = 1;

    const char *override_name = getenv("NATIVE_ACCELERATOR_VULKAN_LIBRARY");
    const char *explicit_name = getenv("MC_VULKAN_LIBRARY_NAME");
    const char *library_path = getenv("VULKAN_LIBRARY_PATH");
#if defined(NAR_DEFAULT_VULKAN_LIBRARY_NAME)
    if (explicit_name == NULL || explicit_name[0] == '\0') explicit_name = NAR_DEFAULT_VULKAN_LIBRARY_NAME;
#endif
#if defined(NAR_DEFAULT_VULKAN_LIBRARY_PATH)
    if (library_path == NULL || library_path[0] == '\0') library_path = NAR_DEFAULT_VULKAN_LIBRARY_PATH;
#endif
    const char *candidates[32];
    char candidate_storage[32][1024];
    size_t candidate_storage_count = 0;
    size_t count = 0;
    if (override_name != NULL && override_name[0] != '\0') candidates[count++] = override_name;
    /* MC_VULKAN_LIBRARY_NAME may be an absolute path. If it is only a basename, try it below
     * the explicit VULKAN_LIBRARY_PATH before allowing the process default search path to win. */
    if (explicit_name != NULL && explicit_name[0] == '/') candidates[count++] = explicit_name;

    /* Unix-family hosts commonly carry several Mesa trees; select the newest Vulkan-enabled one
     * before anything is opened so the system loader and VK_DRIVER_FILES agree on that tree. */
    nar_vulkan_mesa_apply_selection();

    nar_apply_moltenvk_defaults();

    /* Explicit launcher paths take precedence over bare names. The generated absolute candidates
     * are retained in local storage until the dlopen loop finishes. When a basename was supplied,
     * try that exact requested file before generic loader names in the same directory. */
    if (library_path != NULL && library_path[0] != '\0') {
        if (explicit_name != NULL && explicit_name[0] != '\0' && explicit_name[0] != '/') {
            char explicit_path[1024];
            int written = snprintf(explicit_path, sizeof(explicit_path), "%s/%s", library_path, explicit_name);
            if (written > 0 && (size_t)written < sizeof(explicit_path)) {
                nar_add_loader_candidate(candidates, &count, sizeof(candidates) / sizeof(candidates[0]),
                                         candidate_storage, &candidate_storage_count, explicit_path);
            }
        }
        nar_add_loader_directory(candidates, &count, sizeof(candidates) / sizeof(candidates[0]),
                                 candidate_storage, &candidate_storage_count, library_path);
    } else if (explicit_name != NULL && explicit_name[0] != '\0' && explicit_name[0] != '/') {
        candidates[count++] = explicit_name;
    }

    char mesa_loader[1024];
    if (nar_vulkan_mesa_loader(mesa_loader, sizeof(mesa_loader)) > 0 && mesa_loader[0] != '\0'
            && count < sizeof(candidates) / sizeof(candidates[0])) {
        candidates[count++] = mesa_loader;
    }

#if defined(_WIN32)
    candidates[count++] = "vulkan-1.dll";
#elif defined(__APPLE__)
    candidates[count++] = "libvulkan.1.dylib";
    candidates[count++] = "libvulkan.dylib";
    candidates[count++] = "libMoltenVK.dylib";
#else
    /* Names the Unix families use: Solaris/illumos, the BSDs, Linux and AIX ship .so, HP-UX uses
     * .sl, and AIX also provides shared libraries as .a archives. */
    candidates[count++] = "libvulkan.so.1";
    candidates[count++] = "libvulkan.so";
    candidates[count++] = "libvulkan.sl";
    candidates[count++] = "libvulkan.sl.1";
    candidates[count++] = "libvulkan.a";
#endif

    for (size_t i = 0; i < count; ++i) {
        nar_library_handle h = nar_open_library(candidates[i]);
        if (h == 0) continue;
        if (nar_find_symbol(h, "vkGetInstanceProcAddr") == NULL) {
            nar_close_library(h);
            continue;
        }
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

void *nar_vulkan_get_proc_address(const char *name) {
    nar_probe_loader();
    if (g_loader.handle == 0 || name == NULL) return NULL;
    return nar_find_symbol(g_loader.handle, name);
}

static uint32_t nar_enumerate_devices_inprocess(void) {
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

/*
 * Physical-device count.
 *
 * Creating a Vulkan instance is not a safe operation on every driver. A MoltenVK build can abort()
 * inside the Apple Metal driver -- observed as the Metal symbol
 * -[MTLIOAccelDevice newIndirectArgumentEncoderWithLayout:] aborting on a GPU without
 * indirect-argument-encoder support. abort() cannot be caught in-process, so on POSIX the probe runs
 * in a forked child that reports the count over a pipe and then exits.
 *
 * nar_apply_moltenvk_defaults() avoids that driver path on macOS by disabling MoltenVK's Metal
 * argument buffers. The isolation below remains as a second line of defence for other drivers that
 * may abort or hang while creating an instance.
 *
 * If the child aborts, hangs, or exits abnormally the parent survives, reports zero devices, and the
 * Vulkan client renderer is simply not selected; the game still launches and plays with its own
 * renderer plus the native compute accelerator.
 *
 * The strategy is platform-specific (see nar_probe_use_inprocess()): on macOS the accurate in-process
 * probe is the default because fork() is unreliable in a threaded graphical process, while on other
 * POSIX systems the fork-isolated probe remains the default. Set NATIVE_ACCELERATOR_VULKAN_PROBE_INPROCESS
 * or NATIVE_ACCELERATOR_VULKAN_PROBE_ISOLATED to force a specific strategy.
 */
#if defined(_WIN32)

uint32_t nar_vulkan_physical_device_count(void) {
    nar_probe_loader();
    if (g_loader.handle == 0) return 0;
    return nar_enumerate_devices_inprocess();
}

#else

#define NAR_VULKAN_PROBE_TIMEOUT_MS 5000

/*
 * Decide the probe strategy.
 *
 * Fork isolation protects against a driver that abort()s while creating a Vulkan instance. On macOS,
 * however, fork() is not a safe isolation primitive for a graphical process: Minecraft (and this repos
 * test harness) run worker threads, and forking a multithreaded process while the Objective-C runtime
 * is initializing makes the child abort inside the ObjC runtime (objc_initializeAfterForkError). The
 * parent then misreads that as "no Vulkan device" -- a false negative on machines where Vulkan works.
 * Setting OBJC_DISABLE_INITIALIZE_FORK_SAFETY from inside the library cannot help, because the ObjC
 * runtime reads it once, at process start, before main().
 *
 * The specific abort that motivated isolation -- MoltenVK building a Metal argument-buffer encoder on
 * a GPU without indirect-argument-encoder support -- is already disabled by nar_apply_moltenvk_defaults().
 * So on macOS the accurate in-process probe is preferred, and the fork path stays available as an
 * explicit opt-in for other drivers that may abort or hang.
 */
static int nar_probe_use_inprocess(void) {
    if (getenv("NATIVE_ACCELERATOR_VULKAN_PROBE_INPROCESS") != NULL) return 1;
#if defined(__APPLE__)
    if (getenv("NATIVE_ACCELERATOR_VULKAN_PROBE_ISOLATED") == NULL) return 1;
#endif
    return 0;
}

static uint32_t nar_probe_device_count_isolated(void) {
    if (nar_probe_use_inprocess()) {
        return nar_enumerate_devices_inprocess();
    }

    int fds[2];
    if (pipe(fds) != 0) return 0;

    pid_t pid = fork();
    if (pid < 0) {
        close(fds[0]);
        close(fds[1]);
        return 0;
    }
    if (pid == 0) {
        close(fds[0]);
        uint32_t child_count = nar_enumerate_devices_inprocess();
        ssize_t written = write(fds[1], &child_count, sizeof(child_count));
        (void)written;
        close(fds[1]);
        _exit(0);
    }

    close(fds[1]);
    uint32_t count = 0;
    struct pollfd pfd;
    pfd.fd = fds[0];
    pfd.events = POLLIN;
    pfd.revents = 0;
    int ready = poll(&pfd, 1, NAR_VULKAN_PROBE_TIMEOUT_MS);
    if (ready > 0) {
        unsigned char *dst = (unsigned char *)&count;
        size_t got = 0;
        while (got < sizeof(count)) {
            ssize_t n = read(fds[0], dst + got, sizeof(count) - got);
            if (n <= 0) break;
            got += (size_t)n;
        }
        if (got != sizeof(count)) count = 0;
    } else {
        kill(pid, SIGKILL);
    }
    close(fds[0]);

    int status = 0;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) { }
    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) count = 0;
    return count;
}

uint32_t nar_vulkan_physical_device_count(void) {
    nar_probe_loader();
    if (g_loader.handle == 0) return 0;
    return nar_probe_device_count_isolated();
}

#endif
