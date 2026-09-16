#include "native_accelerator_renderer.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define CHECK(expr) do { if (!(expr)) { fprintf(stderr, "CHECK failed: %s (%s:%d)\n", #expr, __FILE__, __LINE__); exit(1); } } while (0)

static void test_arena(void) {
    nar_arena *arena = nar_arena_create(1024, 16);
    CHECK(arena != NULL);
    uint64_t a = UINT64_MAX, b = UINT64_MAX;
    CHECK(nar_arena_alloc(arena, 100, 64, &a) == 0);
    CHECK(a == 0);
    CHECK(nar_arena_alloc(arena, 100, 64, &b) == 0);
    CHECK(b == 128);
    nar_arena_stats stats;
    CHECK(nar_arena_get_stats(arena, &stats) == 0);
    CHECK(stats.used == 200);
    CHECK(stats.allocation_count == 2);
    CHECK(nar_arena_free(arena, a, 100) == 0);
    CHECK(nar_arena_free(arena, b, 100) == 0);
    CHECK(nar_arena_get_stats(arena, &stats) == 0);
    CHECK(stats.used == 0);
    CHECK(stats.free_block_count == 1);
    CHECK(stats.largest_free_block == 1024);
    nar_arena_destroy(arena);
}

static void test_scene(void) {
    nar_scene *scene = nar_scene_create(4);
    CHECK(scene != NULL);
    CHECK(nar_scene_upsert(scene, 11, -1, -1, -1, 1, 1, 1, 0, 36, 0, 5, 1) == 0);
    CHECK(nar_scene_upsert(scene, 22, 200, 0, 0, 216, 16, 16, 36, 42, 24, 6, 1) == 0);
    CHECK(nar_scene_count(scene) == 2);

    const float planes[24] = {
         1, 0, 0, 100,
        -1, 0, 0, 100,
         0, 1, 0, 100,
         0,-1, 0, 100,
         0, 0, 1, 100,
         0, 0,-1, 100
    };
    nar_draw_indexed_indirect_command command[4];
    uint64_t keys[4];
    uint32_t count = 0;
    CHECK(nar_scene_build_indirect(scene, planes, 0, 0, 0, 0, 0, command, keys, 4, &count) == 0);
    CHECK(count == 1);
    CHECK(keys[0] == 11);
    CHECK(command[0].index_count == 36);
    CHECK(command[0].first_instance == 5);

    float mins[8], maxs[8];
    uint8_t meta[40];
    uint64_t export_keys[2];
    uint32_t exported = 0;
    CHECK(nar_scene_export_gpu(scene, mins, maxs, meta, export_keys, 2, &exported) == 0);
    CHECK(exported == 2);

    CHECK(nar_scene_remove(scene, 11) == 0);
    CHECK(nar_scene_count(scene) == 1);
    nar_scene_destroy(scene);
}

static void test_face_masks(void) {
    uint8_t occupancy[4096];
    uint8_t masks[4096];
    memset(occupancy, 0, sizeof(occupancy));
    occupancy[0] = 1; /* x=0,y=0,z=0 */
    occupancy[1] = 1; /* x=1,y=0,z=0 */
    CHECK(nar_voxel_face_masks(masks, occupancy, NULL) == 0);
    CHECK((masks[0] & NAR_FACE_POS_X) == 0);
    CHECK((masks[1] & NAR_FACE_NEG_X) == 0);
    CHECK((masks[0] & NAR_FACE_NEG_X) != 0);
    CHECK((masks[1] & NAR_FACE_POS_X) != 0);

    uint8_t neighbors[1536];
    memset(neighbors, 0, sizeof(neighbors));
    neighbors[0] = 1; /* -X plane, z=0,y=0 */
    CHECK(nar_voxel_face_masks(masks, occupancy, neighbors) == 0);
    CHECK((masks[0] & NAR_FACE_NEG_X) == 0);
}

static void test_parallel_batch(void) {
    nar_context *context = nar_context_create(4);
    CHECK(context != NULL);
    CHECK(nar_context_worker_count(context) >= 1);
    const uint32_t sections = 8;
    uint8_t *occupancy = (uint8_t *)calloc((size_t)sections, 4096);
    uint8_t *masks = (uint8_t *)calloc((size_t)sections, 4096);
    CHECK(occupancy != NULL && masks != NULL);
    for (uint32_t i = 0; i < sections; ++i) occupancy[(size_t)i * 4096 + (i & 15u)] = 1;
    CHECK(nar_voxel_face_masks_batch(context, masks, occupancy, NULL, sections) == 0);
    for (uint32_t i = 0; i < sections; ++i) CHECK(masks[(size_t)i * 4096 + (i & 15u)] != 0);
    free(masks);
    free(occupancy);
    nar_context_destroy(context);
}

static void test_cache_hash(void) {
    const char data[] = "native-accelerator-pipeline";
    uint64_t a = nar_pipeline_cache_key(data, sizeof(data) - 1, 7);
    uint64_t b = nar_pipeline_cache_key(data, sizeof(data) - 1, 7);
    uint64_t c = nar_pipeline_cache_key(data, sizeof(data) - 1, 8);
    CHECK(a == b);
    CHECK(a != c);
}

static char *write_temp_options(const char *body) {
    static char path[512];
    snprintf(path, sizeof(path), "%s/nativeaccelerator-options-%ld.txt", getenv("TMPDIR") != NULL ? getenv("TMPDIR") : ".",
             (long)getpid());
    FILE *f = fopen(path, "wb");
    CHECK(f != NULL);
    if (body != NULL) {
        fwrite(body, 1u, strlen(body), f);
    }
    fclose(f);
    return path;
}

/*
 * Client-renderer policy is derived from runtime evidence, never from the OS name. These cases pin the
 * decision rule by feeding the parser real options.txt content and by exercising the explicit override.
 */
static void test_renderer_platform_policy(void) {
    uint32_t host_role = nar_renderer_platform_role();
    uint32_t evidence = nar_renderer_platform_evidence();
    uint64_t caps = nar_capabilities();

    /* Capabilities must always agree with the resolved role, whatever the host turns out to be. */
    CHECK(host_role == NAR_RENDERER_ROLE_CLIENT || host_role == NAR_RENDERER_ROLE_SERVER_ONLY);
    if (host_role == NAR_RENDERER_ROLE_CLIENT) {
        CHECK(nar_renderer_client_eligible() == 1);
        CHECK((caps & NAR_CAP_CLIENT_RENDERER) != 0);
        CHECK((caps & NAR_CAP_SERVER_ONLY_HOST) == 0);
        CHECK((evidence & NAR_RENDERER_EVIDENCE_VULKAN_DEVICE) != 0);
    } else {
        CHECK(nar_renderer_client_eligible() == 0);
        CHECK((caps & NAR_CAP_CLIENT_RENDERER) == 0);
        CHECK((caps & NAR_CAP_SERVER_ONLY_HOST) != 0);
    }
    CHECK((evidence & NAR_RENDERER_EVIDENCE_OVERRIDE) == 0);

    /* The override is honoured in both directions and never mutates evidence discovery. */
    nar_renderer_set_role_override(NAR_RENDERER_ROLE_CLIENT);
    CHECK(nar_renderer_platform_role() == NAR_RENDERER_ROLE_CLIENT);
    CHECK(nar_renderer_client_eligible() == 1);
    CHECK((nar_renderer_platform_evidence() & NAR_RENDERER_EVIDENCE_OVERRIDE) != 0);
    nar_renderer_set_role_override(NAR_RENDERER_ROLE_SERVER_ONLY);
    CHECK(nar_renderer_platform_role() == NAR_RENDERER_ROLE_SERVER_ONLY);
    CHECK(nar_renderer_client_eligible() == 0);
    nar_renderer_set_role_override(NAR_RENDERER_ROLE_AUTO);
    CHECK(nar_renderer_platform_role() == host_role);

    /* Missing options.txt must not be mistaken for an OpenGL/non-Vulkan choice. */
    char missing[512];
    snprintf(missing, sizeof(missing), "%s/nativeaccelerator-absent-%ld.txt",
             getenv("TMPDIR") != NULL ? getenv("TMPDIR") : ".", (long)getpid());
    remove(missing);
    nar_renderer_set_options_path(missing);
    CHECK((nar_renderer_platform_evidence() & NAR_RENDERER_EVIDENCE_MINECRAFT_OPTIONS) == 0);

    /* An explicit OpenGL/other backend in options.txt is real evidence that the Vulkan client renderer
     * has nothing to attach to on this machine, regardless of OS or architecture. */
    nar_renderer_set_options_path(write_temp_options("graphicsApi:opengl\nfov:0.5\nguiScale:2\n"));
    CHECK((nar_renderer_platform_evidence() & NAR_RENDERER_EVIDENCE_MINECRAFT_OPTIONS) != 0);
    CHECK((nar_renderer_platform_evidence() & NAR_RENDERER_EVIDENCE_MINECRAFT_NON_VULKAN) != 0);
    CHECK(nar_renderer_platform_role() == NAR_RENDERER_ROLE_SERVER_ONLY);

    /* A Vulkan backend choice is likewise real evidence. */
    nar_renderer_set_options_path(write_temp_options("graphicsApi:vulkan\nfov:0.5\n"));
    CHECK((nar_renderer_platform_evidence() & NAR_RENDERER_EVIDENCE_MINECRAFT_VULKAN) != 0);
    CHECK((nar_renderer_platform_evidence() & NAR_RENDERER_EVIDENCE_MINECRAFT_NON_VULKAN) == 0);

    /* Real Minecraft option spellings and case handling. */
    nar_renderer_set_options_path(write_temp_options("renderer:OpenGL\n"));
    CHECK((nar_renderer_platform_evidence() & NAR_RENDERER_EVIDENCE_MINECRAFT_NON_VULKAN) != 0);
    nar_renderer_set_options_path(write_temp_options("graphicsBackend=VULKAN\r\n"));
    CHECK((nar_renderer_platform_evidence() & NAR_RENDERER_EVIDENCE_MINECRAFT_VULKAN) != 0);

    /* Unrelated keys must not be read as backend choices. */
    nar_renderer_set_options_path(write_temp_options("fov:0.5\nrenderDistance:12\nparticles:all\n"));
    CHECK((nar_renderer_platform_evidence() & NAR_RENDERER_EVIDENCE_MINECRAFT_NON_VULKAN) == 0);
    CHECK((nar_renderer_platform_evidence() & NAR_RENDERER_EVIDENCE_MINECRAFT_VULKAN) == 0);

    /* A Vulkan line anywhere in the file is decisive over a later non-Vulkan line. */
    nar_renderer_set_options_path(write_temp_options("renderer:vulkan\nrenderer:opengl\n"));
    CHECK((nar_renderer_platform_evidence() & NAR_RENDERER_EVIDENCE_MINECRAFT_VULKAN) != 0);

    /* The reported path is the path actually consulted, and the override clears cleanly. */
    char reported[512];
    CHECK(nar_renderer_options_path(reported, sizeof(reported)) > 0);
    CHECK(strstr(reported, "nativeaccelerator-options-") != NULL);
    nar_renderer_set_options_path(NULL);
    CHECK((nar_renderer_platform_evidence() & NAR_RENDERER_EVIDENCE_OVERRIDE) == 0);

    /* Status text is descriptive rather than a bare OS label, and truncates safely. */
    char platform[256];
    uint64_t length = nar_renderer_platform_name(platform, sizeof(platform));
    CHECK(length > 0);
    CHECK(strstr(platform, "vulkan=") != NULL);
    CHECK(strstr(platform, "minecraft=") != NULL);
    CHECK(strstr(platform, "server-only") != NULL || strstr(platform, "client-eligible") != NULL);
    CHECK(nar_renderer_platform_name(NULL, 0) == length);

    char tiny[6];
    CHECK(nar_renderer_platform_name(tiny, sizeof(tiny)) == length);
    CHECK(strlen(tiny) == sizeof(tiny) - 1);

    char backend[512];
    nar_backend_name(backend, sizeof(backend));
    CHECK(strlen(backend) > 0);
    CHECK(nar_backend_name(NULL, 0) > 0);
}

/* ------------------------------------------------------------------ *
 * Mesa installation selection: the Solaris/illumos /opt policy, portable here.
 *
 * This file half is deliberately written without backslash escapes so the
 * quote characters it needs come from the MESA_Q character constant.
 * ------------------------------------------------------------------ */

static const char MESA_Q = '"';

static void mesa_write_file(const char *path, const char *text) {
    FILE *f = fopen(path, "wb");
    if (f == NULL) { fprintf(stderr, "WRITE FAIL path=%s", path); }
    CHECK(f != NULL);
    fputs(text, f);
    fclose(f);
}

static void mesa_make_dirs(const char *path) {
    char buffer[1024];
    size_t length = strlen(path);
    if (length >= sizeof(buffer)) return;
    memcpy(buffer, path, length + 1u);
    for (size_t i = 1; i < length; ++i) {
        if (buffer[i] == '/') {
            buffer[i] = 0;
            (void)mkdir(buffer, 0777);
            buffer[i] = '/';
        }
    }
}

/* Create the directory named by path itself, including its parents. */
static void mesa_make_dir(const char *path) {
    mesa_make_dirs(path);
    (void)mkdir(path, 0777);
}

/*
 * Build one fake Mesa tree. With driver_token non-NULL the tree is Vulkan-enabled: it gets an ICD
 * manifest whose library_path points at a driver library this helper really creates. Without a
 * driver_token the tree represents a Mesa built with no Vulkan driver at all, and must never be
 * selected no matter how new its version looks.
 */
static void mesa_make_tree(const char *prefix, const char *driver_token) {
    if (driver_token == NULL) return; /* Vulkan-disabled tree: no ICD manifest exists */

    char driver[1024];
    snprintf(driver, sizeof(driver), "%s/share/vulkan/%s_driver.so", prefix, driver_token);
    mesa_make_dirs(driver);
    mesa_write_file(driver, "stub driver; only its existence is checked");

    char manifest[1024];
    snprintf(manifest, sizeof(manifest), "%s/share/vulkan/icd.d/mesa_%s.json", prefix, driver_token);
    mesa_make_dirs(manifest);

    char body[2048];
    snprintf(body, sizeof(body),
             "{%cfile_format_version%c: %c1.0.0%c, %cICD%c: {%clibrary_path%c: %c%s%c}}",
             MESA_Q, MESA_Q, MESA_Q, MESA_Q, MESA_Q, MESA_Q, MESA_Q, MESA_Q, MESA_Q, driver, MESA_Q);
    mesa_write_file(manifest, body);
}

/* A manifest naming a driver that does not exist must not make a tree look Vulkan-enabled. */
static void mesa_make_broken_tree(const char *prefix) {
    char manifest[1024];
    snprintf(manifest, sizeof(manifest), "%s/share/vulkan/icd.d/mesa_missing.json", prefix);
    mesa_make_dirs(manifest);

    static const char missing[] = "/nonexistent/nativeaccelerator/no-such-driver.so";
    char body[2048];
    snprintf(body, sizeof(body),
             "{%cfile_format_version%c: %c1.0.0%c, %cICD%c: {%clibrary_path%c: %c%s%c}}",
             MESA_Q, MESA_Q, MESA_Q, MESA_Q, MESA_Q, MESA_Q, MESA_Q, MESA_Q, MESA_Q, missing, MESA_Q);
    mesa_write_file(manifest, body);
}

static void mesa_rm_rf(const char *path) {
    char command[2048];
    snprintf(command, sizeof(command), "rm -rf '%s'", path);
    (void)system(command);
}

static void test_mesa_selection(void) {
    const char *tmp = getenv("TMPDIR") != NULL ? getenv("TMPDIR") : ".";
    char root[1024], picked[1024];
    snprintf(root, sizeof(root), "%s/nativeaccelerator-mesa-%ld", tmp, (long)getpid());
    snprintf(picked, sizeof(picked), "%s/nativeaccelerator-mesa-picked-%ld", tmp, (long)getpid());
    mesa_rm_rf(root);
    mesa_rm_rf(picked);
    (void)mkdir(root, 0777);
    (void)mkdir(picked, 0777);

    char older[1024], disabled[1024], newer[1024], broken[1024], best[1024];
    snprintf(older, sizeof(older), "%s/mesa-24.1.0", root);
    snprintf(disabled, sizeof(disabled), "%s/mesa-26.9.9", root); /* newest number, but no Vulkan */
    snprintf(newer, sizeof(newer), "%s/mesa-25.0.2", root);
    snprintf(broken, sizeof(broken), "%s/mesa-27.0.0", root); /* newest number, driver missing */
    snprintf(best, sizeof(best), "%s/mesa-25.4.0", picked);

    mesa_make_tree(older, "older");
    mesa_make_tree(newer, "newer");
    mesa_make_tree(best, "best");
    mesa_make_dir(disabled);       /* Vulkan-disabled tree */
    mesa_make_broken_tree(broken);  /* Vulkan-enabled-looking tree with a missing driver */

    (void)unsetenv("NATIVE_ACCELERATOR_MESA_PREFIX");
    (void)unsetenv("NATIVE_ACCELERATOR_MESA_PIN");
    /* This test drives selection from the environment only, so its result must not depend on which
     * Mesa trees the machine running it happens to have installed. */
    CHECK(setenv("NATIVE_ACCELERATOR_MESA_NO_DEFAULTS", "1", 1) == 0);
    (void)unsetenv("VK_DRIVER_FILES");
    (void)unsetenv("VK_ICD_FILENAMES");
    CHECK(setenv("NATIVE_ACCELERATOR_MESA_SEARCH_PATH", root, 1) == 0);

    char prefix[1024];
    uint64_t length = nar_vulkan_mesa_prefix(prefix, sizeof(prefix));
    CHECK(length > 0);
    CHECK(nar_vulkan_mesa_prefix(NULL, 0) == length);
    /* Newest Vulkan-enabled tree wins: 25.0.2, not the Vulkan-disabled 26.9.9 nor the broken 27.0.0. */
    CHECK(strcmp(prefix, newer) == 0);

    /* A tree that ships its own Vulkan loader is reported by full path. */
    char own_loader[1024];
    snprintf(own_loader, sizeof(own_loader), "%s/lib/libvulkan.so.1", newer);
    mesa_make_dirs(own_loader);
    mesa_write_file(own_loader, "stub loader");
    char reported[1024];
    CHECK(nar_vulkan_mesa_loader(reported, sizeof(reported)) > 0);
    CHECK(strcmp(reported, own_loader) == 0);
    CHECK(nar_vulkan_mesa_loader(NULL, 0) == strlen(own_loader));

    /* When the selected tree has its own loader, the loader default ICD search is kept: nothing pinned. */
    nar_vulkan_mesa_apply_selection();
    CHECK(getenv("VK_DRIVER_FILES") == NULL);

    /* An explicit prefix is a candidate too, and the newest version still wins across all of them. */
    CHECK(setenv("NATIVE_ACCELERATOR_MESA_PREFIX", picked, 1) == 0);
    CHECK(nar_vulkan_mesa_prefix(prefix, sizeof(prefix)) > 0);
    CHECK(strcmp(prefix, best) == 0);

    /* The selected tree ships no loader, so the ICD list is pinned to that one tree. */
    nar_vulkan_mesa_apply_selection();
    const char *pinned = getenv("VK_DRIVER_FILES");
    CHECK(pinned != NULL);
    CHECK(strstr(pinned, best) != NULL);
    CHECK(strstr(pinned, "mesa_best.json") != NULL);
    (void)unsetenv("VK_DRIVER_FILES");
    (void)unsetenv("VK_ICD_FILENAMES");

    /* An operator who already set the ICD variables is never overridden. */
    CHECK(setenv("VK_DRIVER_FILES", "/operator/choice.json", 1) == 0);
    nar_vulkan_mesa_apply_selection();
    CHECK(strcmp(getenv("VK_DRIVER_FILES"), "/operator/choice.json") == 0);
    (void)unsetenv("VK_DRIVER_FILES");

    /* A prefix list on its own selects from that list. */
    (void)unsetenv("NATIVE_ACCELERATOR_MESA_SEARCH_PATH");
    CHECK(setenv("NATIVE_ACCELERATOR_MESA_PREFIX", older, 1) == 0);
    CHECK(nar_vulkan_mesa_prefix(prefix, sizeof(prefix)) > 0);
    CHECK(strcmp(prefix, older) == 0);
    (void)unsetenv("NATIVE_ACCELERATOR_MESA_PREFIX");

    /* With only Vulkan-disabled trees there is nothing to select, rather than a wrong guess. */
    char none_root[1024];
    snprintf(none_root, sizeof(none_root), "%s/nativeaccelerator-mesa-none-%ld", tmp, (long)getpid());
    mesa_rm_rf(none_root);
    char only_disabled[1024];
    snprintf(only_disabled, sizeof(only_disabled), "%s/mesa-99.0.0", none_root);
    mesa_make_dirs(only_disabled);
    CHECK(setenv("NATIVE_ACCELERATOR_MESA_SEARCH_PATH", none_root, 1) == 0);
    CHECK(nar_vulkan_mesa_prefix(prefix, sizeof(prefix)) == 0);
    CHECK(prefix[0] == 0);
    CHECK(nar_vulkan_mesa_loader(reported, sizeof(reported)) == 0);

    (void)unsetenv("NATIVE_ACCELERATOR_MESA_SEARCH_PATH");
    (void)unsetenv("NATIVE_ACCELERATOR_MESA_NO_DEFAULTS");
    mesa_rm_rf(none_root);
    mesa_rm_rf(picked);
    mesa_rm_rf(root);
}



static void ul_mkdirs(const char *path) {
    char buf[1024];
    snprintf(buf, sizeof(buf), "%s", path);
    for (char *p = buf + 1; *p; ++p) {
        if (*p == '/') { *p = 0; mkdir(buf, 0777); *p = '/'; }
    }
    mkdir(buf, 0777);
}

static void ul_make_parent(const char *file) {
    char dir[1024];
    snprintf(dir, sizeof(dir), "%s", file);
    char *slash = strrchr(dir, '/');
    if (slash) { *slash = 0; ul_mkdirs(dir); }
}

static void ul_write_file(const char *path, const char *body) {
    ul_make_parent(path);
    FILE *f = fopen(path, "wb");
    if (!f) { printf("CANNOT WRITE FILE: %s", path); puts(""); exit(1); }
    fputs(body, f);
    fclose(f);
}

/* Create prefix/<drv_dir>/mesa_drv.so plus an ICD manifest under prefix/<manifest_dir>, and
   optionally prefix/<loader_rel>. */
static void ul_tree(const char *prefix, const char *drv_dir,
                 const char *manifest_dir, const char *loader_rel) {
    char p[1024];
    snprintf(p, sizeof(p), "%s/%s/mesa_drv.so", prefix, drv_dir);
    ul_write_file(p, "stub");

    char man[1024], body[2048];
    snprintf(man, sizeof(man), "%s/%s/mesa.json", prefix, manifest_dir);
    snprintf(body, sizeof(body),
        "{file_format_version: 1.0.0, ICD: {library_path: \"%s/%s/mesa_drv.so\"}}",
        prefix, drv_dir);
    ul_write_file(man, body);

    if (loader_rel) {
        char l[1024];
        snprintf(l, sizeof(l), "%s/%s", prefix, loader_rel);
        ul_write_file(l, "stub loader");
    }
}

static const char *ul_sel_prefix(void) {
    static char buf[1024];
    nar_vulkan_mesa_prefix(buf, sizeof(buf));
    return buf;
}

static const char *ul_sel_loader(void) {
    static char buf[1024];
    nar_vulkan_mesa_loader(buf, sizeof(buf));
    return buf;
}

static int ul_failures = 0;

static void ul_check(const char *label, const char *got, const char *want) {
    (void)label;
    CHECK(strcmp(got, want) == 0);
}

/* Fresh isolated root plus a search path pointing only at it. */
static void ul_use_root(char *root, size_t n, const char *tmp, const char *name) {
    snprintf(root, n, "%s/na-%s-%ld", tmp, name, (long)getpid());
    ul_mkdirs(root);
    (void)setenv("NATIVE_ACCELERATOR_MESA_SEARCH_PATH", root, 1);
    (void)setenv("NATIVE_ACCELERATOR_MESA_NO_DEFAULTS", "1", 1);
    (void)unsetenv("NATIVE_ACCELERATOR_MESA_PREFIX");
    (void)unsetenv("NATIVE_ACCELERATOR_MESA_PIN");
    (void)unsetenv("VK_DRIVER_FILES");
    (void)unsetenv("VK_ICD_FILENAMES");
}

static void test_mesa_unix_layouts(void) {
    const char *tmp = getenv("TMPDIR") ? getenv("TMPDIR") : ".";
    char root[1024], t[1024], want[1024];

    /* HP-UX: shared libraries are .sl, and 64-bit ones live in lib/hpux64. */
    ul_use_root(root, sizeof(root), tmp, "hpux");
    snprintf(t, sizeof(t), "%s/mesa-25.0.0", root);
    ul_tree(t, "lib/hpux64", "share/vulkan/icd.d", "lib/hpux64/libvulkan.sl");
    ul_check("HP-UX prefix (.sl in lib/hpux64)", ul_sel_prefix(), t);
    snprintf(want, sizeof(want), "%s/lib/hpux64/libvulkan.sl", t);
    ul_check("HP-UX loader .sl", ul_sel_loader(), want);

    /* AIX: lib64 and shared-library archives. */
    ul_use_root(root, sizeof(root), tmp, "aix");
    snprintf(t, sizeof(t), "%s/mesa-24.0.0", root);
    ul_tree(t, "lib", "etc/vulkan/icd.d", "lib64/libvulkan.a");
    ul_check("AIX prefix (lib64/libvulkan.a)", ul_sel_prefix(), t);
    snprintf(want, sizeof(want), "%s/lib64/libvulkan.a", t);
    ul_check("AIX loader .a", ul_sel_loader(), want);

    /* BSD: everything under lib. */
    ul_use_root(root, sizeof(root), tmp, "bsd");
    snprintf(t, sizeof(t), "%s/mesa-23.0.0", root);
    ul_tree(t, "lib", "share/vulkan/icd.d", "lib/libvulkan.so.1");
    ul_check("BSD prefix (lib/libvulkan.so.1)", ul_sel_prefix(), t);
    snprintf(want, sizeof(want), "%s/lib/libvulkan.so.1", t);
    ul_check("BSD loader .so.1", ul_sel_loader(), want);

    /* Linux multiarch subdirectory. */
    ul_use_root(root, sizeof(root), tmp, "lnx");
    snprintf(t, sizeof(t), "%s/mesa-22.0.0", root);
    ul_tree(t, "lib", "share/vulkan/icd.d", "lib/x86_64-linux-gnu/libvulkan.so.1");
    ul_check("Linux multiarch prefix", ul_sel_prefix(), t);
    snprintf(want, sizeof(want), "%s/lib/x86_64-linux-gnu/libvulkan.so.1", t);
    ul_check("Linux multiarch loader", ul_sel_loader(), want);

    /* A ul_tree with no loader of its own is still selectable; its ICD list gets pinned because the
       system loader would otherwise search its own unrelated paths. */
    ul_use_root(root, sizeof(root), tmp, "noloader");
    snprintf(t, sizeof(t), "%s/mesa-21.0.0", root);
    ul_tree(t, "lib", "share/vulkan/icd.d", NULL);
    ul_check("loader-less prefix still selected", ul_sel_prefix(), t);
    ul_check("loader-less reports empty loader", ul_sel_loader(), "");
    nar_vulkan_mesa_apply_selection();
    int pinned = getenv("VK_DRIVER_FILES") != 0;
    if (!pinned) ++ul_failures;
    printf("%s ICD list pinned when ul_tree has no loader", pinned ? "PASS" : "FAIL");
    puts("");
    (void)unsetenv("VK_DRIVER_FILES");
    (void)unsetenv("VK_ICD_FILENAMES");

    /* A versioned loader with no fixed name is found by the directory scan. */
    ul_use_root(root, sizeof(root), tmp, "vers");
    snprintf(t, sizeof(t), "%s/mesa-30.0.0", root);
    ul_tree(t, "lib", "share/vulkan/icd.d", NULL);
    char vdl[1024];
    snprintf(vdl, sizeof(vdl), "%s/lib/libvulkan.so.3.0.0", t);
    ul_write_file(vdl, "stub versioned loader");
    ul_check("versioned loader found by scan", ul_sel_loader(), vdl);

    /* The opt-out disables platform defaults entirely. */
    (void)unsetenv("NATIVE_ACCELERATOR_MESA_PREFIX");
    (void)unsetenv("NATIVE_ACCELERATOR_MESA_SEARCH_PATH");
    (void)setenv("NATIVE_ACCELERATOR_MESA_NO_DEFAULTS", "1", 1);
    ul_check("NO_DEFAULTS ignores platform defaults", ul_sel_prefix(), "");
    (void)unsetenv("NATIVE_ACCELERATOR_MESA_NO_DEFAULTS");

    char cmd[2048];
    snprintf(cmd, sizeof(cmd), "rm -rf '%s/na-hpux-*' '%s/na-aix-*' '%s/na-bsd-*' "
             "'%s/na-lnx-*' '%s/na-noloader-*' '%s/na-vers-*'",
             tmp, tmp, tmp, tmp, tmp, tmp);
    (void)system(cmd);
    printf("RESULT: %s", ul_failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
    puts("");
    
}

int main(void) {
    CHECK(nar_abi_version() == NAR_ABI_VERSION);
    CHECK((nar_capabilities() & NAR_CAP_SCENE_DATABASE) != 0);
    test_arena();
    test_scene();
    test_face_masks();
    test_parallel_batch();
    test_cache_hash();
    test_renderer_platform_policy();
    test_mesa_selection();
    test_mesa_unix_layouts();

    char name[256];
    nar_backend_name(name, sizeof(name));
    printf("Renderer backend: %s\n", name);
    char platform[256];
    nar_renderer_platform_name(platform, sizeof(platform));
    printf("Platform policy: %s\n", platform);
    printf("Vulkan loader available: %d, api=0x%08x, device-evidence=%d\n",
           nar_vulkan_loader_available(), nar_vulkan_loader_api_version(),
           (nar_renderer_platform_evidence() & NAR_RENDERER_EVIDENCE_VULKAN_DEVICE) != 0);
    puts("Native Accelerator renderer tests passed");
    return 0;
}
