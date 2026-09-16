#include "native_accelerator_renderer.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/*
 * Client-renderer runtime policy.
 *
 * Nothing here is an operating-system allow-list. The role is derived from two runtime facts:
 *
 *   1. Vulkan device evidence: the loader is opened and physical devices are enumerated. A Mesa or
 *      vendor build compiled without usable Vulkan support yields no device even on an OS where Vulkan
 *      is otherwise expected, and a machine that does provide Vulkan yields a device even when that is
 *      unusual for its OS.
 *
 *   2. Minecraft backend evidence: options.txt in the Minecraft directory is parsed for the graphics
 *      backend the game itself is configured to use.
 *
 * A role override is always honoured, so users are never blocked by a wrong guess.
 */

#define NAR_OPTIONS_MAX_BYTES (256u * 1024u)
#define NAR_PATH_MAX          1024u

static uint32_t g_role_override = NAR_RENDERER_ROLE_AUTO;
static char g_options_path[NAR_PATH_MAX];
static int g_options_path_fixed;
static int g_options_probed;
static int g_options_found;
static uint32_t g_options_backend; /* 0 unknown, 1 vulkan, 2 non-vulkan */
static int g_vulkan_checked;
static int g_vulkan_device_ready;

static char nar_lower(char c) {
    if (c >= 'A' && c <= 'Z') return (char)(c - 'A' + 'a');
    return c;
}

static int nar_contains_ci(const char *haystack, size_t length, const char *needle) {
    size_t n = strlen(needle);
    if (n == 0 || length < n) return 0;
    for (size_t i = 0; i + n <= length; ++i) {
        size_t k = 0;
        while (k < n && nar_lower(haystack[i + k]) == needle[k]) ++k;
        if (k == n) return 1;
    }
    return 0;
}

static int nar_copy(char *dst, size_t capacity, const char *src) {
    if (dst == 0 || capacity == 0 || src == 0) return 0;
    size_t length = strlen(src);
    if (length >= capacity) return 0;
    memcpy(dst, src, length + 1u);
    return 1;
}

static void nar_trim(const char **begin, const char **end) {
    while (*begin < *end && (**begin == ' ' || **begin == '\t' || **begin == '\r' || **begin == '\n')) ++*begin;
    while (*end > *begin) {
        char c = *(*end - 1);
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') --*end;
        else break;
    }
}

static int nar_file_readable(const char *path) {
    if (path == 0 || path[0] == '\0') return 0;
    FILE *f = fopen(path, "rb");
    if (f == 0) return 0;
    fclose(f);
    return 1;
}

/* Discover the Minecraft options.txt path when the caller did not provide one. */
static void nar_discover_options_path(void) {
    if (g_options_path_fixed) return;
    g_options_path[0] = '\0';

    const char *explicit_env = getenv("NATIVE_ACCELERATOR_MINECRAFT_OPTIONS");
    if (explicit_env != 0 && explicit_env[0] != '\0' && nar_file_readable(explicit_env)) {
        (void)nar_copy(g_options_path, sizeof(g_options_path), explicit_env);
        return;
    }

    char candidate[NAR_PATH_MAX];
    const char *minecraft_home = getenv("MINECRAFT_HOME");
    if (minecraft_home != 0 && minecraft_home[0] != '\0') {
        if (snprintf(candidate, sizeof(candidate), "%s/options.txt", minecraft_home) > 0 && nar_file_readable(candidate)) {
            (void)nar_copy(g_options_path, sizeof(g_options_path), candidate);
            return;
        }
    }

    static const char *relative[] = {
        "options.txt",
        "run/options.txt",
        "minecraft/options.txt",
    };
    for (size_t i = 0; i < sizeof(relative) / sizeof(relative[0]); ++i) {
        if (nar_file_readable(relative[i])) {
            (void)nar_copy(g_options_path, sizeof(g_options_path), relative[i]);
            return;
        }
    }

    const char *home = getenv("HOME");
    const char *appdata = getenv("APPDATA");
    if (home != 0 && home[0] != '\0') {
        if (snprintf(candidate, sizeof(candidate), "%s/Library/Application Support/minecraft/options.txt", home) > 0
                && nar_file_readable(candidate)) {
            (void)nar_copy(g_options_path, sizeof(g_options_path), candidate);
            return;
        }
        if (snprintf(candidate, sizeof(candidate), "%s/.minecraft/options.txt", home) > 0
                && nar_file_readable(candidate)) {
            (void)nar_copy(g_options_path, sizeof(g_options_path), candidate);
            return;
        }
    }
    if (appdata != 0 && appdata[0] != '\0') {
        if (snprintf(candidate, sizeof(candidate), "%s/.minecraft/options.txt", appdata) > 0
                && nar_file_readable(candidate)) {
            (void)nar_copy(g_options_path, sizeof(g_options_path), candidate);
            return;
        }
    }
}

static uint32_t nar_classify_backend_value(const char *value, size_t length) {
    if (nar_contains_ci(value, length, "vulkan")) return 1u;
    if (value != 0 && length > 0) {
        if (nar_contains_ci(value, length, "opengl")
                || nar_contains_ci(value, length, "d3d")
                || nar_contains_ci(value, length, "directx")
                || nar_contains_ci(value, length, "direct3d")
                || nar_contains_ci(value, length, "metal")
                || nar_contains_ci(value, length, "angle")
                || nar_contains_ci(value, length, "software")
                || nar_contains_ci(value, length, "compat")) {
            return 2u;
        }
    }
    return 0u;
}

static int nar_is_backend_key(const char *key, size_t length) {
    return nar_contains_ci(key, length, "graphicsapi")
            || nar_contains_ci(key, length, "graphics_api")
            || nar_contains_ci(key, length, "graphicsbackend")
            || nar_contains_ci(key, length, "renderer")
            || nar_contains_ci(key, length, "backend")
            || nar_contains_ci(key, length, "vulkan")
            || nar_contains_ci(key, length, "rhi");
}

static void nar_parse_options_text(const char *text, size_t length) {
    size_t i = 0;
    while (i < length) {
        size_t line_end = i;
        while (line_end < length && text[line_end] != '\n') ++line_end;
        const char *begin = text + i;
        const char *end = text + line_end;
        i = line_end + 1u;

        /* Minecraft writes key:value; some launchers and option writers emit key=value. */
        const char *cursor = begin;
        while (cursor < end && *cursor != ':' && *cursor != '=') ++cursor;
        if (cursor >= end) continue; /* no key/value separator */

        const char *key = begin;
        const char *key_end = cursor;
        nar_trim(&key, &key_end);
        if (key_end <= key) continue;

        const char *value = cursor + 1;
        const char *value_end = end;
        nar_trim(&value, &value_end);

        if (!nar_is_backend_key(key, (size_t)(key_end - key))) continue;

        uint32_t classified = nar_classify_backend_value(value, (size_t)(value_end - value));
        if (classified == 1u) {
            g_options_backend = 1u;
            return; /* an explicit Vulkan backend is decisive */
        }
        if (classified == 2u && g_options_backend == 0u) {
            g_options_backend = 2u;
        }
    }
}

static void nar_probe_options(void) {
    if (g_options_probed) return;
    g_options_probed = 1;
    nar_discover_options_path();
    if (g_options_path[0] == '\0') return;

    FILE *f = fopen(g_options_path, "rb");
    if (f == 0) return;

    char *buffer = (char *)malloc(NAR_OPTIONS_MAX_BYTES);
    if (buffer == 0) {
        fclose(f);
        return;
    }
    size_t read = fread(buffer, 1u, NAR_OPTIONS_MAX_BYTES - 1u, f);
    fclose(f);
    buffer[read] = '\0';
    g_options_found = 1;
    nar_parse_options_text(buffer, read);
    free(buffer);
}

static int nar_vulkan_device_ready(void) {
    if (!g_vulkan_checked) {
        g_vulkan_checked = 1;
        g_vulkan_device_ready = (nar_vulkan_loader_available() && nar_vulkan_physical_device_count() > 0) ? 1 : 0;
    }
    return g_vulkan_device_ready;
}

uint32_t nar_renderer_platform_role(void) {
    if (g_role_override == NAR_RENDERER_ROLE_CLIENT) return NAR_RENDERER_ROLE_CLIENT;
    if (g_role_override == NAR_RENDERER_ROLE_SERVER_ONLY) return NAR_RENDERER_ROLE_SERVER_ONLY;

    /* Question one is the Minecraft backend, and it is asked first because it is both cheap and
     * decisive. When Minecraft itself runs a non-Vulkan backend the Vulkan client renderer rewrite
     * has nothing to attach to, so the role is already known and the Vulkan device probe cannot
     * change it. Skipping the probe also avoids creating a Vulkan instance on loader builds whose
     * instance creation aborts the process (observed with MoltenVK on macOS), which would otherwise
     * take the whole game down before it finished starting. */
    nar_probe_options();
    if (g_options_backend == 2u) return NAR_RENDERER_ROLE_SERVER_ONLY;

    /* Question two: does this computer actually provide a Vulkan device? Only asked when it can
     * still change the decision. */
    if (!nar_vulkan_device_ready()) return NAR_RENDERER_ROLE_SERVER_ONLY;

    return NAR_RENDERER_ROLE_CLIENT;
}

int32_t nar_renderer_client_eligible(void) {
    return nar_renderer_platform_role() == NAR_RENDERER_ROLE_CLIENT ? 1 : 0;
}

uint32_t nar_renderer_platform_evidence(void) {
    uint32_t evidence = 0;
    if (nar_vulkan_loader_available()) evidence |= NAR_RENDERER_EVIDENCE_VULKAN_LOADER;

    /* Report device evidence only when the device probe is safe to run. While Minecraft is known to
     * run a non-Vulkan backend the device answer cannot matter, and creating a Vulkan instance may
     * abort on some loader builds, so it is skipped rather than forced for diagnostics. */
    nar_probe_options();
    if (g_options_backend != 2u && nar_vulkan_device_ready()) {
        evidence |= NAR_RENDERER_EVIDENCE_VULKAN_DEVICE;
    }

    if (g_options_found) {
        evidence |= NAR_RENDERER_EVIDENCE_MINECRAFT_OPTIONS;
        if (g_options_backend == 1u) evidence |= NAR_RENDERER_EVIDENCE_MINECRAFT_VULKAN;
        else if (g_options_backend == 2u) evidence |= NAR_RENDERER_EVIDENCE_MINECRAFT_NON_VULKAN;
    }
    if (g_role_override != NAR_RENDERER_ROLE_AUTO) evidence |= NAR_RENDERER_EVIDENCE_OVERRIDE;
    return evidence;
}

uint64_t nar_renderer_platform_name(char *dst, uint64_t capacity) {
    uint32_t evidence = nar_renderer_platform_evidence();
    const char *vulkan = (evidence & NAR_RENDERER_EVIDENCE_VULKAN_DEVICE) ? "device-ready"
                       : (evidence & NAR_RENDERER_EVIDENCE_VULKAN_LOADER) ? "loader-only"
                       : "unavailable";
    const char *minecraft = (evidence & NAR_RENDERER_EVIDENCE_MINECRAFT_VULKAN) ? "vulkan"
                          : (evidence & NAR_RENDERER_EVIDENCE_MINECRAFT_NON_VULKAN) ? "non-vulkan"
                          : (evidence & NAR_RENDERER_EVIDENCE_MINECRAFT_OPTIONS) ? "unknown"
                          : "no-options";
    const char *role = nar_renderer_client_eligible() ? "client-eligible" : "server-only";

    char text[256];
    (void)snprintf(text, sizeof(text), "vulkan=%s minecraft=%s (%s)%s",
                   vulkan, minecraft, role,
                   (evidence & NAR_RENDERER_EVIDENCE_OVERRIDE) ? " [override]" : "");

    uint64_t length = (uint64_t)strlen(text);
    if (dst != 0 && capacity > 0) {
        uint64_t copy = length < capacity - 1u ? length : capacity - 1u;
        memcpy(dst, text, (size_t)copy);
        dst[copy] = '\0';
    }
    return length;
}

void nar_renderer_set_options_path(const char *path) {
    if (path != 0 && path[0] != '\0') {
        (void)nar_copy(g_options_path, sizeof(g_options_path), path);
        g_options_path_fixed = 1;
    } else {
        g_options_path[0] = '\0';
        g_options_path_fixed = 0;
    }
    g_options_probed = 0;
    g_options_found = 0;
    g_options_backend = 0u;
}

uint64_t nar_renderer_options_path(char *dst, uint64_t capacity) {
    nar_probe_options();
    uint64_t length = (uint64_t)strlen(g_options_path);
    if (dst != 0 && capacity > 0) {
        uint64_t copy = length < capacity - 1u ? length : capacity - 1u;
        memcpy(dst, g_options_path, (size_t)copy);
        dst[copy] = '\0';
    }
    return length;
}

void nar_renderer_set_role_override(uint32_t role) {
    g_role_override = role;
}
