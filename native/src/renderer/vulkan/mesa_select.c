/*
 * Mesa installation discovery for Unix-family hosts.
 *
 * The same problem appears on every Unix-like host: Solaris/illumos, the BSDs, Linux, HP-UX and AIX
 * commonly carry more than one Mesa tree at once. There can be a system tree (/usr, /usr/local), a
 * ports or pkgsrc tree (/usr/pkg, /opt/local), and one or more third-party trees unpacked under
 * /opt (for example /opt/mesa-24.1.0, /opt/freeware or /opt/csw). Only some of those trees are
 * built with a Vulkan driver; an older tree may provide libGL and no Vulkan ICD at all. The
 * candidate set is configuration, so the same small rule works everywhere.
 *
 * This file answers one question: which single Mesa tree should the renderer companion library
 * load? The rule is deliberately small and auditable:
 *
 *   1. A tree is a candidate only when it really provides a Vulkan ICD: at least one driver
 *      manifest under <prefix>/share/vulkan/icd.d or <prefix>/etc/vulkan/icd.d whose referenced
 *      driver library exists on disk. A Mesa built without Vulkan has no such manifest and is
 *      skipped, so Vulkan-disabled trees can never be selected.
 *   2. Among candidates the newest parsed version wins. Versions are read from the installation
 *      path (mesa-25.0.2 -> 25.0.2); a tree named after a year (opt/2025/mesa-25.0) still parses
 *      as 25.0 because a bare year has no minor component.
 *   3. A known version beats an unknown one, so an /opt tree that advertises a version is
 *      preferred over an unversioned system install.
 *   4. Ties fall back to owning its own Vulkan loader, then to discovery order (explicit
 *      prefixes, then the system defaults, then the enumerated search roots). With no versions
 *      anywhere the system default therefore wins, which is the conservative choice.
 *
 * None of this is Solaris-only behaviour: the platform defaults, the prefixes and the search roots
 * are configuration, so the selection logic is unit tested on any POSIX host and works unchanged on
 * the BSDs, Linux, HP-UX, AIX and the other Unix families.
 *
 * Environment:
 *   NATIVE_ACCELERATOR_MESA_PREFIX       colon-separated roots to enumerate first (the prefix
 *                                        and its immediate subdirectories become candidates)
 *   NATIVE_ACCELERATOR_MESA_SEARCH_PATH  further roots enumerated the same way, after the
 *                                        explicit prefixes
 *   NATIVE_ACCELERATOR_MESA_PIN=1        force the VK_DRIVER_FILES/VK_ICD_FILENAMES environment
 *                                        from the selected tree even when it has its own loader
 *   NATIVE_ACCELERATOR_MESA_NO_DEFAULTS  set to anything but 0 to ignore the platform default
 *                                        locations and select only from the environment above
 *
 * Not thread-safe by design: these structures are process-wide bootstrap state consulted once,
 * during renderer probe, before any worker thread is created.
 */

#include "native_accelerator_renderer.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define NAR_MESA_PATH_MAX 1024u
#define NAR_MESA_MAX_CANDIDATES 32
#define NAR_MESA_MAX_ICDS 32
#define NAR_MESA_DRIVER_LIST_MAX 4096u
#define NAR_MESA_MANIFEST_MAX_BYTES (64u * 1024u)

#if defined(_WIN32)

/* Mesa discovery has no meaning on Windows; keep the symbols so the library links identically on
 * every platform, and report nothing. */

uint64_t nar_vulkan_mesa_prefix(char *dst, uint64_t capacity) {
    if (dst != 0 && capacity > 0) dst[0] = '\0';
    return 0;
}

uint64_t nar_vulkan_mesa_loader(char *dst, uint64_t capacity) {
    if (dst != 0 && capacity > 0) dst[0] = '\0';
    return 0;
}

void nar_vulkan_mesa_apply_selection(void) {
}

#else

#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>

typedef struct nar_mesa_version {
    int known;
    unsigned major;
    unsigned minor;
    unsigned patch;
} nar_mesa_version;

typedef struct nar_mesa_candidate {
    char prefix[NAR_MESA_PATH_MAX];
    char loader[NAR_MESA_PATH_MAX];
    char icd_dir[NAR_MESA_PATH_MAX];
    unsigned icd_count;
    int discovery_order;
    nar_mesa_version version;
} nar_mesa_candidate;

/* Loader locations inside a Mesa prefix, covering the Unix families. Solaris puts 64-bit libraries
 * in lib/64 or lib/amd64 and SPARC in lib/sparcv9; Linux distributions use multiarch names such as
 * lib/x86_64-linux-gnu; HP-UX uses lib/hpux64 (lib/pa20_64 on PA-RISC); AIX uses lib64; the BSDs
 * and most other Unix systems put both 32- and 64-bit libraries in lib or lib64. */
static const char *nar_mesa_loader_subdirs[] = {
    "lib",
    "lib64",
    "lib/64",
    "lib/amd64",
    "lib/sparcv9",
    "lib/hpux64",
    "lib/pa20_64",
    "lib/x86_64-linux-gnu",
    "lib/aarch64-linux-gnu",
    "lib/i386-linux-gnu",
    "lib/arm-linux-gnueabihf",
    "lib/hppa-linux-gnu",
    "lib/powerpc64-linux-gnu",
    "lib/powerpc64le-linux-gnu",
    "lib/s390x-linux-gnu",
    "lib/riscv64-linux-gnu",
    "lib/ia64-linux-gnu"
};

/* Names a Vulkan loader can carry on the Unix families. The fixed names are tried first so the
 * stable entry points win when several exist; the scan below catches versioned or platform-only
 * names such as libvulkan.so.1.3.290. */
static const char *nar_mesa_loader_names[] = {
    "libvulkan.so.1",   /* Solaris/illumos, the BSDs, Linux, AIX */
    "libvulkan.so",
    "libvulkan.sl",     /* HP-UX shared libraries */
    "libvulkan.sl.1",
    "libvulkan.a"       /* AIX shared-library archive */
};

static const char *nar_mesa_icd_subdirs[] = {
    "share/vulkan/icd.d",
    "etc/vulkan/icd.d"
};

/* Process-wide scratch. The scan is repeated per entry point rather than cached so tests and
 * diagnostics can change the environment; the renderer probe calls it once and caches the
 * outcome in its own loader state. */
static nar_mesa_candidate g_candidates[NAR_MESA_MAX_CANDIDATES];

static void nar_mesa_copy(char *dst, size_t capacity, const char *src) {
    if (dst == 0 || capacity == 0) return;
    dst[0] = '\0';
    if (src == 0) return;
    size_t length = strlen(src);
    if (length > capacity - 1u) length = capacity - 1u;
    memcpy(dst, src, length);
    dst[length] = '\0';
}

static void nar_mesa_join(char *dst, size_t capacity, const char *base, const char *suffix) {
    if (dst == 0 || capacity == 0) return;
    dst[0] = '\0';
    if (base == 0) return;
    if (suffix == 0 || suffix[0] == '\0') {
        (void)snprintf(dst, capacity, "%s", base);
        return;
    }
    size_t base_length = strlen(base);
    if (base_length > 0 && base[base_length - 1u] == '/') {
        (void)snprintf(dst, capacity, "%s%s", base, suffix);
    } else {
        (void)snprintf(dst, capacity, "%s/%s", base, suffix);
    }
}

static int nar_mesa_is_dir(const char *path) {
    struct stat st;
    if (path == 0 || path[0] == '\0') return 0;
    if (stat(path, &st) != 0) return 0;
    return S_ISDIR(st.st_mode) ? 1 : 0;
}

static int nar_mesa_is_file(const char *path) {
    struct stat st;
    if (path == 0 || path[0] == '\0') return 0;
    if (stat(path, &st) != 0) return 0;
    return S_ISREG(st.st_mode) ? 1 : 0;
}

/*
 * Read the newest-looking version out of an installation path. A run only counts when it has at
 * least a major and a minor component, which rejects the incidental numbers in names such as
 * lib/x86_64, lib/64 and /opt/2025 (a bare year). The last qualifying run wins, so
 * /opt/2025/mesa-24.1.0/lib/64 parses as 24.1.0.
 */
static void nar_mesa_parse_version(const char *text, nar_mesa_version *out) {
    out->known = 0;
    out->major = 0;
    out->minor = 0;
    out->patch = 0;
    if (text == 0) return;

    unsigned best_major = 0;
    unsigned best_minor = 0;
    unsigned best_patch = 0;
    int found = 0;

    size_t length = strlen(text);
    size_t i = 0;
    while (i < length) {
        if (text[i] < '0' || text[i] > '9') {
            ++i;
            continue;
        }
        unsigned parts[3] = { 0u, 0u, 0u };
        int component_count = 0;
        size_t j = i;
        while (component_count < 3) {
            unsigned value = 0;
            int digits = 0;
            while (j < length && text[j] >= '0' && text[j] <= '9') {
                if (digits < 6) value = value * 10u + (unsigned)(text[j] - '0');
                ++digits;
                ++j;
            }
            if (digits == 0) break;
            parts[component_count++] = value;
            if (j + 1u < length && text[j] == '.' && text[j + 1u] >= '0' && text[j + 1u] <= '9') {
                ++j;
                continue;
            }
            break;
        }
        if (component_count >= 2) {
            best_major = parts[0];
            best_minor = parts[1];
            best_patch = component_count > 2 ? parts[2] : 0u;
            found = 1;
        }
        i = (j > i) ? j : i + 1u;
    }

    if (!found) return;
    out->known = 1;
    out->major = best_major;
    out->minor = best_minor;
    out->patch = best_patch;
}

/* List one directory for a libvulkan under any name. Reached only after the fixed names are
 * missing, so it catches versioned loaders (libvulkan.so.1.3.290) and the platform-specific
 * spellings (libvulkan.sl on HP-UX, libvulkan.a on AIX). */
static int nar_mesa_scan_loader_flat(const char *directory, char *out, size_t capacity) {
    DIR *dir = opendir(directory);
    if (dir == 0) return 0;

    int found = 0;
    struct dirent *entry;
    while (!found && (entry = readdir(dir)) != 0) {
        if (strncmp(entry->d_name, "libvulkan", 9u) != 0) continue;
        char candidate[NAR_MESA_PATH_MAX];
        nar_mesa_join(candidate, sizeof(candidate), directory, entry->d_name);
        if (nar_mesa_is_file(candidate)) {
            nar_mesa_copy(out, capacity, candidate);
            found = 1;
        }
    }
    closedir(dir);
    return found;
}

static int nar_mesa_find_loader(const char *prefix, char *out, size_t capacity) {
    /* 64-bit libraries sit one level below lib on several Unix systems. */
    static const char *nested[] = { "64", "amd64", "sparcv9", "hpux64", "hpux32", "pa20_64" };

    char candidate[NAR_MESA_PATH_MAX];
    for (size_t s = 0; s < sizeof(nar_mesa_loader_subdirs) / sizeof(nar_mesa_loader_subdirs[0]); ++s) {
        for (size_t n = 0; n < sizeof(nar_mesa_loader_names) / sizeof(nar_mesa_loader_names[0]); ++n) {
            char relative[NAR_MESA_PATH_MAX];
            (void)snprintf(relative, sizeof(relative), "%s/%s", nar_mesa_loader_subdirs[s], nar_mesa_loader_names[n]);
            nar_mesa_join(candidate, sizeof(candidate), prefix, relative);
            if (nar_mesa_is_file(candidate)) {
                nar_mesa_copy(out, capacity, candidate);
                return 1;
            }
        }

        char directory[NAR_MESA_PATH_MAX];
        nar_mesa_join(directory, sizeof(directory), prefix, nar_mesa_loader_subdirs[s]);
        if (nar_mesa_scan_loader_flat(directory, out, capacity)) return 1;

        for (size_t i = 0; i < sizeof(nested) / sizeof(nested[0]); ++i) {
            char deeper[NAR_MESA_PATH_MAX];
            nar_mesa_join(deeper, sizeof(deeper), directory, nested[i]);
            if (nar_mesa_scan_loader_flat(deeper, out, capacity)) return 1;
        }
    }
    return 0;
}

static void nar_mesa_extract_library_path(const char *text, size_t length, char *out, size_t capacity) {
    if (out == 0 || capacity == 0) return;
    out[0] = '\0';
    if (text == 0) return;

    const char *key = strstr(text, "library_path");
    if (key == 0) return;

    const char *end = text + length;
    const char *cursor = key + strlen("library_path");
    /* Step over the colon separating the key from its value, then any whitespace,
       then the quote that opens the value itself. */
    while (cursor < end && *cursor != ':') ++cursor;
    if (cursor >= end) return;
    ++cursor;
    while (cursor < end && (*cursor == ' ' || *cursor == '\t' || *cursor == '\n' || *cursor == '\r')) ++cursor;
    if (cursor >= end || *cursor != '"') return;
    ++cursor;
    const char *value_end = cursor;
    while (value_end < end && *value_end != '"' && *value_end != '\n') ++value_end;
    size_t value_length = (size_t)(value_end - cursor);
    if (value_length == 0) return;
    if (value_length > capacity - 1u) value_length = capacity - 1u;
    memcpy(out, cursor, value_length);
    out[value_length] = '\0';
}

/*
 * A manifest counts only when it is a Vulkan ICD that points at a driver library which really
 * exists. A relative library_path is resolved against the manifest directory, which is what the
 * Vulkan loader specification requires.
 */
static int nar_mesa_manifest_is_usable(const char *manifest_path) {
    FILE *file = fopen(manifest_path, "rb");
    if (file == 0) return 0;

    char *buffer = (char *)malloc(NAR_MESA_MANIFEST_MAX_BYTES);
    if (buffer == 0) {
        fclose(file);
        return 0;
    }
    size_t read = fread(buffer, 1u, NAR_MESA_MANIFEST_MAX_BYTES - 1u, file);
    fclose(file);
    buffer[read] = '\0';
    if (read == 0) {
        free(buffer);
        return 0;
    }

    char library[NAR_MESA_PATH_MAX];
    nar_mesa_extract_library_path(buffer, read, library, sizeof(library));
    free(buffer);
    if (library[0] == '\0') return 0;
    if (library[0] == '/') return nar_mesa_is_file(library);

    char directory[NAR_MESA_PATH_MAX];
    nar_mesa_copy(directory, sizeof(directory), manifest_path);
    char *slash = strrchr(directory, '/');
    if (slash == 0) return nar_mesa_is_file(library);
    *slash = '\0';

    char resolved[NAR_MESA_PATH_MAX];
    nar_mesa_join(resolved, sizeof(resolved), directory, library);
    return nar_mesa_is_file(resolved);
}

/* Count the usable Vulkan ICD manifests under a prefix and report the first manifest directory. */
static unsigned nar_mesa_scan_icds(const char *prefix, char *first_dir, size_t capacity) {
    unsigned count = 0;
    char directory[NAR_MESA_PATH_MAX];

    for (size_t s = 0; s < sizeof(nar_mesa_icd_subdirs) / sizeof(nar_mesa_icd_subdirs[0]); ++s) {
        nar_mesa_join(directory, sizeof(directory), prefix, nar_mesa_icd_subdirs[s]);
        DIR *dir = opendir(directory);
        if (dir == 0) continue;

        struct dirent *entry;
        while ((entry = readdir(dir)) != 0) {
            if (entry->d_name[0] == '.') continue;
            size_t name_length = strlen(entry->d_name);
            if (name_length < 6u) continue;
            if (strcmp(entry->d_name + name_length - 5u, ".json") != 0) continue;

            char manifest[NAR_MESA_PATH_MAX];
            nar_mesa_join(manifest, sizeof(manifest), directory, entry->d_name);
            if (!nar_mesa_manifest_is_usable(manifest)) continue;

            if (count == 0 && first_dir != 0) nar_mesa_copy(first_dir, capacity, directory);
            ++count;
            if (count >= NAR_MESA_MAX_ICDS) break;
        }
        closedir(dir);
    }
    return count;
}

static void nar_mesa_add_candidate(size_t *count, const char *prefix) {
    if (count == 0 || prefix == 0 || prefix[0] == '\0') return;
    if (!nar_mesa_is_dir(prefix)) return;
    if (*count >= NAR_MESA_MAX_CANDIDATES) return;

    for (size_t i = 0; i < *count; ++i) {
        if (strcmp(g_candidates[i].prefix, prefix) == 0) return;
    }

    nar_mesa_candidate *candidate = &g_candidates[*count];
    memset(candidate, 0, sizeof(*candidate));
    nar_mesa_copy(candidate->prefix, sizeof(candidate->prefix), prefix);
    candidate->discovery_order = (int)*count;
    (void)nar_mesa_find_loader(prefix, candidate->loader, sizeof(candidate->loader));
    candidate->icd_count = nar_mesa_scan_icds(prefix, candidate->icd_dir, sizeof(candidate->icd_dir));
    nar_mesa_parse_version(prefix, &candidate->version);
    ++*count;
}

/* A search root contributes itself and each of its immediate subdirectories. */
static void nar_mesa_add_root(size_t *count, const char *root) {
    if (root == 0 || root[0] == '\0') return;
    nar_mesa_add_candidate(count, root);

    DIR *dir = opendir(root);
    if (dir == 0) return;

    struct dirent *entry;
    while ((entry = readdir(dir)) != 0) {
        if (entry->d_name[0] == '.') continue;
        char path[NAR_MESA_PATH_MAX];
        nar_mesa_join(path, sizeof(path), root, entry->d_name);
        if (nar_mesa_is_dir(path)) nar_mesa_add_candidate(count, path);
    }
    closedir(dir);
}

static void nar_mesa_add_colon_list(size_t *count, const char *list, int as_roots) {
    if (list == 0 || list[0] == '\0') return;
    const char *cursor = list;
    while (*cursor != '\0') {
        const char *separator = strchr(cursor, ':');
        size_t length = separator == 0 ? strlen(cursor) : (size_t)(separator - cursor);
        if (length > 0 && length < NAR_MESA_PATH_MAX) {
            char entry[NAR_MESA_PATH_MAX];
            memcpy(entry, cursor, length);
            entry[length] = '\0';
            if (as_roots) nar_mesa_add_root(count, entry);
            else nar_mesa_add_candidate(count, entry);
        }
        if (separator == 0) break;
        cursor = separator + 1;
    }
}

/*
 * Default locations, shared by the Unix families: Solaris/illumos, the BSDs (FreeBSD, NetBSD,
 * OpenBSD, DragonFly), Linux, HP-UX, AIX and the other Unix-like systems. The same handful of
 * layouts covers all of them:
 *
 *   /usr, /usr/local       the system tree and the locally built tree
 *   /usr/pkg               pkgsrc (NetBSD, SmartOS, and pkgsrc-based Linux)
 *   /opt/local             the /opt/<vendor> layout, as on MacPorts
 *   /opt + subdirectories  versioned or vendor trees such as /opt/mesa-24.1.0, /opt/freeware
 *                          (AIX) or /opt/csw (OpenCSW on Solaris)
 *
 * Listing all of them is safe on every host: a tree is only ever selected when it really provides a
 * usable Vulkan ICD manifest, so a Vulkan-less default can never be chosen over a Vulkan-enabled
 * one, and a machine whose only Vulkan-enabled tree is the system default still works.
 *
 * macOS is intentionally excluded. Its Vulkan support arrives through MoltenVK, whose dylib is
 * handled by the loader probe, and a Homebrew or MacPorts tree must not be mistaken for a Mesa tree
 * that this selection could drive. The environment variables above still work there.
 *
 * NATIVE_ACCELERATOR_MESA_NO_DEFAULTS, when set to anything other than 0, skips these locations
 * entirely so the host is driven purely by the environment; tests and explicitly managed
 * deployments use it to stay independent of what the machine happens to have installed.
 */
static void nar_mesa_add_defaults(size_t *count) {
    const char *disabled = getenv("NATIVE_ACCELERATOR_MESA_NO_DEFAULTS");
    if (disabled != 0 && disabled[0] != 0 && strcmp(disabled, "0") != 0) return;

#if defined(__APPLE__)
    (void)count; /* MoltenVK, not Mesa */
#else
    nar_mesa_add_candidate(count, "/usr");
    nar_mesa_add_candidate(count, "/usr/local");
    nar_mesa_add_candidate(count, "/usr/pkg");
    nar_mesa_add_candidate(count, "/opt/local");
    nar_mesa_add_root(count, "/opt");
#endif
}

static size_t nar_mesa_collect(void) {
    size_t count = 0;
    nar_mesa_add_colon_list(&count, getenv("NATIVE_ACCELERATOR_MESA_PREFIX"), 1);
    nar_mesa_add_colon_list(&count, getenv("NATIVE_ACCELERATOR_MESA_SEARCH_PATH"), 1);
    nar_mesa_add_defaults(&count);
    return count;
}

static int nar_mesa_version_compare(const nar_mesa_version *a, const nar_mesa_version *b) {
    if (a->known != b->known) return a->known ? 1 : -1;
    if (!a->known) return 0;
    if (a->major != b->major) return a->major > b->major ? 1 : -1;
    if (a->minor != b->minor) return a->minor > b->minor ? 1 : -1;
    if (a->patch != b->patch) return a->patch > b->patch ? 1 : -1;
    return 0;
}

/* True when a is the better choice. Only Vulkan-enabled candidates are ever compared. */
static int nar_mesa_better(const nar_mesa_candidate *a, const nar_mesa_candidate *b) {
    int comparison = nar_mesa_version_compare(&a->version, &b->version);
    if (comparison != 0) return comparison > 0;

    int a_has_loader = a->loader[0] != '\0';
    int b_has_loader = b->loader[0] != '\0';
    if (a_has_loader != b_has_loader) return a_has_loader;

    return a->discovery_order < b->discovery_order;
}

/* The newest Vulkan-enabled Mesa tree, or NULL when the machine advertises none. */
static const nar_mesa_candidate *nar_mesa_selected(void) {
    size_t count = nar_mesa_collect();
    const nar_mesa_candidate *best = 0;
    for (size_t i = 0; i < count; ++i) {
        if (g_candidates[i].icd_count == 0) continue; /* not built with Vulkan */
        if (best == 0 || nar_mesa_better(&g_candidates[i], best)) best = &g_candidates[i];
    }
    return best;
}

uint64_t nar_vulkan_mesa_prefix(char *dst, uint64_t capacity) {
    const nar_mesa_candidate *selected = nar_mesa_selected();
    const char *prefix = selected != 0 ? selected->prefix : "";
    uint64_t length = (uint64_t)strlen(prefix);
    if (dst != 0 && capacity > 0) {
        uint64_t copy = length < capacity - 1u ? length : capacity - 1u;
        memcpy(dst, prefix, (size_t)copy);
        dst[copy] = '\0';
    }
    return length;
}

uint64_t nar_vulkan_mesa_loader(char *dst, uint64_t capacity) {
    const nar_mesa_candidate *selected = nar_mesa_selected();
    const char *loader = selected != 0 ? selected->loader : "";
    uint64_t length = (uint64_t)strlen(loader);
    if (dst != 0 && capacity > 0) {
        uint64_t copy = length < capacity - 1u ? length : capacity - 1u;
        memcpy(dst, loader, (size_t)copy);
        dst[copy] = '\0';
    }
    return length;
}

static void nar_mesa_append(char *dst, size_t capacity, const char *value) {
    if (dst == 0 || capacity == 0 || value == 0) return;
    size_t used = strlen(dst);
    if (used + 1u >= capacity) return;

    if (used > 0) {
        dst[used++] = ':';
        dst[used] = '\0';
    }
    size_t available = capacity - used - 1u;
    size_t length = strlen(value);
    if (length > available) length = available;
    memcpy(dst + used, value, length);
    dst[used + length] = '\0';
}

static void nar_mesa_build_driver_files(const char *directory, char *dst, size_t capacity) {
    dst[0] = '\0';
    DIR *dir = opendir(directory);
    if (dir == 0) return;

    struct dirent *entry;
    while ((entry = readdir(dir)) != 0) {
        if (entry->d_name[0] == '.') continue;
        size_t name_length = strlen(entry->d_name);
        if (name_length < 6u) continue;
        if (strcmp(entry->d_name + name_length - 5u, ".json") != 0) continue;

        char manifest[NAR_MESA_PATH_MAX];
        nar_mesa_join(manifest, sizeof(manifest), directory, entry->d_name);
        if (!nar_mesa_manifest_is_usable(manifest)) continue;
        nar_mesa_append(dst, capacity, manifest);
    }
    closedir(dir);
}

/*
 * Make the selected tree the one the Vulkan loader actually uses.
 *
 * The primary mechanism is loading that tree own loader, whose default ICD search is relative to
 * its own location. Pinning VK_DRIVER_FILES is only needed when the selected tree has no loader of
 * its own (so the system loader would otherwise look elsewhere), and it never overrides an
 * operator who already set those variables. It is deliberately not applied when the tree has its
 * own loader, because narrowing the ICD list could then hide an unrelated vendor driver.
 */
void nar_vulkan_mesa_apply_selection(void) {
    const nar_mesa_candidate *selected = nar_mesa_selected();
    if (selected == 0) return;
    if (selected->icd_dir[0] == '\0') return;

    const char *pin = getenv("NATIVE_ACCELERATOR_MESA_PIN");
    int forced = (pin != 0 && strcmp(pin, "1") == 0);
    if (!forced && selected->loader[0] != '\0') return;

    if (getenv("VK_DRIVER_FILES") != 0 || getenv("VK_ICD_FILENAMES") != 0) return;

    char list[NAR_MESA_DRIVER_LIST_MAX];
    nar_mesa_build_driver_files(selected->icd_dir, list, sizeof(list));
    if (list[0] == '\0') return;

    setenv("VK_DRIVER_FILES", list, 1);
    setenv("VK_ICD_FILENAMES", list, 1); /* honoured by older loaders */
}

#endif
