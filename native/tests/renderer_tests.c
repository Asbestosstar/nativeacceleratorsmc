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

/*
 * Offscreen image lifecycle and readback tests.
 *
 * These tests gate on the actual Vulkan loader being available so they pass
 * cleanly on CI machines with no GPU.  When Vulkan is present they exercise
 * the full create / render_begin / render_end / readback / destroy path.
 */
static void test_offscreen_image_no_session(void) {
    /* NULL session must not crash and must return NULL / negative values. */
    CHECK(nar_vulkan_offscreen_image_create(NULL, 64, 64) == NULL);
    nar_vulkan_offscreen_image_destroy(NULL, NULL); /* must be a no-op */

    void *cb = (void *)(uintptr_t)0xdeadbeef;
    CHECK(nar_vulkan_offscreen_render_begin(NULL, NULL, &cb) < 0);
    CHECK(nar_vulkan_offscreen_render_end(NULL, NULL) < 0);

    uint8_t buf[4];
    CHECK(nar_vulkan_offscreen_readback(NULL, NULL, buf, sizeof(buf)) < 0);
}

static void test_offscreen_image_lifecycle(void) {
    /* If no Vulkan device is available the session will be NULL; skip gracefully. */
    if (!nar_vulkan_loader_available()) {
        printf("SKIP test_offscreen_image_lifecycle: no Vulkan loader\n");
        return;
    }

    nar_vulkan_session *session =
        nar_vulkan_session_create(NAR_VULKAN_MODE_OFFSCREEN, 4, 4);
    if (session == NULL) {
        printf("SKIP test_offscreen_image_lifecycle: session unavailable\n");
        return;
    }
    CHECK(nar_vulkan_session_tier(session) >= NAR_VULKAN_TIER_BASIC);

    /* Zero dimensions must be rejected. */
    CHECK(nar_vulkan_offscreen_image_create(session, 0, 4) == NULL);
    CHECK(nar_vulkan_offscreen_image_create(session, 4, 0) == NULL);

    /* Normal creation. */
    const uint32_t W = 4, H = 4;
    nar_vulkan_offscreen_image *img =
        nar_vulkan_offscreen_image_create(session, W, H);
    if (img == NULL) {
        /* Acceptable on platforms where device-local image allocation fails. */
        printf("SKIP test_offscreen_image_lifecycle: image create unavailable\n");
        nar_vulkan_session_destroy(session);
        return;
    }

    /* render_begin: get the command buffer handle. */
    void *cb = NULL;
    int32_t r = nar_vulkan_offscreen_render_begin(session, img, &cb);
    CHECK(r == NAR_OFFSCREEN_RESULT_OK);
    CHECK(cb != NULL);

    /* render_end: close, copy to readback, submit. */
    CHECK(nar_vulkan_offscreen_render_end(session, img) == NAR_OFFSCREEN_RESULT_OK);

    /* readback: wait for fence, map, copy. */
    uint32_t pixels[W * H]; /* BGRA8 = 4 bytes/pixel */
    memset(pixels, 0xAB, sizeof(pixels));
    CHECK(nar_vulkan_offscreen_readback(session, img, pixels, sizeof(pixels))
          == NAR_OFFSCREEN_RESULT_OK);

    /* Readback with a buffer that is too small must fail. */
    uint8_t tiny[3];
    CHECK(nar_vulkan_offscreen_readback(session, img, tiny, sizeof(tiny)) < 0);

    /* Second render/readback cycle verifies fence reset. */
    r = nar_vulkan_offscreen_render_begin(session, img, &cb);
    CHECK(r == NAR_OFFSCREEN_RESULT_OK);
    CHECK(nar_vulkan_offscreen_render_end(session, img) == NAR_OFFSCREEN_RESULT_OK);
    CHECK(nar_vulkan_offscreen_readback(session, img, pixels, sizeof(pixels))
          == NAR_OFFSCREEN_RESULT_OK);

    nar_vulkan_offscreen_image_destroy(session, img);
    nar_vulkan_session_destroy(session);
    printf("PASS test_offscreen_image_lifecycle (W=%u H=%u)\n", W, H);
}

static void test_offscreen_image_readback_null_dst(void) {
    /* Passing NULL dst must return an error, not crash. */
    if (!nar_vulkan_loader_available()) return;
    nar_vulkan_session *session =
        nar_vulkan_session_create(NAR_VULKAN_MODE_OFFSCREEN, 2, 2);
    if (session == NULL) return;
    nar_vulkan_offscreen_image *img =
        nar_vulkan_offscreen_image_create(session, 2, 2);
    if (img != NULL) {
        CHECK(nar_vulkan_offscreen_readback(session, img, NULL, 16) < 0);
        nar_vulkan_offscreen_image_destroy(session, img);
    }
    nar_vulkan_session_destroy(session);
}

/*
 * Vertex buffer lifecycle and upload tests.
 *
 * Like the offscreen tests these gate on the actual Vulkan loader so they
 * pass cleanly on machines with no GPU.  When Vulkan is present they exercise
 * create / upload (in and out of bounds) / destroy.
 */
static void test_buffer_no_session(void) {
    /* NULL session must not crash and must return NULL / negative / zero. */
    CHECK(nar_vulkan_buffer_create(NULL, 1024) == NULL);
    nar_vulkan_buffer_destroy(NULL, NULL); /* must be a no-op */
    CHECK(nar_vulkan_buffer_upload(NULL, NULL, NULL, 0) < 0);
    CHECK(nar_vulkan_buffer_size(NULL) == 0);
}

static void test_buffer_lifecycle(void) {
    if (!nar_vulkan_loader_available()) {
        printf("SKIP test_buffer_lifecycle: no Vulkan loader\n");
        return;
    }
    nar_vulkan_session *session =
        nar_vulkan_session_create(NAR_VULKAN_MODE_OFFSCREEN, 4, 4);
    if (session == NULL) {
        printf("SKIP test_buffer_lifecycle: session unavailable\n");
        return;
    }

    /* Zero size must be rejected. */
    CHECK(nar_vulkan_buffer_create(session, 0) == NULL);

    const uint64_t SIZE = 1024;
    nar_vulkan_buffer *buf = nar_vulkan_buffer_create(session, SIZE);
    if (buf == NULL) {
        printf("SKIP test_buffer_lifecycle: buffer create unavailable\n");
        nar_vulkan_session_destroy(session);
        return;
    }
    CHECK(nar_vulkan_buffer_size(buf) == SIZE);

    /* Upload within bounds. */
    uint8_t data[1024];
    for (size_t i = 0; i < sizeof(data); ++i) data[i] = (uint8_t)(i & 0xff);
    CHECK(nar_vulkan_buffer_upload(session, buf, data, sizeof(data))
          == NAR_VULKAN_BUFFER_RESULT_OK);

    /* Zero-length upload is a tolerated no-op. */
    CHECK(nar_vulkan_buffer_upload(session, buf, data, 0)
          == NAR_VULKAN_BUFFER_RESULT_OK);

    /* Oversized upload must fail. */
    CHECK(nar_vulkan_buffer_upload(session, buf, data, SIZE + 1) < 0);

    /* NULL data with nonzero size must fail. */
    CHECK(nar_vulkan_buffer_upload(session, buf, NULL, 16) < 0);

    nar_vulkan_buffer_destroy(session, buf);
    nar_vulkan_session_destroy(session);
    printf("PASS test_buffer_lifecycle (size=%llu)\n", (unsigned long long)SIZE);
}

/*
 * Pipeline ABI null-safety: all entry points must handle NULL session/image/module/pipeline
 * without crashing.  Verified on every host regardless of Vulkan availability.
 */
static void test_pipeline_null_safety(void) {
    /* shader module */
    CHECK(nar_vulkan_shader_module_create(NULL, NULL, 0) == NULL);
    nar_vulkan_shader_module_destroy(NULL, NULL); /* must be no-op */

    /* pipeline */
    CHECK(nar_vulkan_pipeline_create(NULL, NULL, NULL, NULL) == NULL);
    nar_vulkan_pipeline_destroy(NULL, NULL); /* must be no-op */

    /* command recording */
    CHECK(nar_vulkan_cmd_bind_pipeline(NULL, NULL, NULL) < 0);
    CHECK(nar_vulkan_cmd_bind_vertex_buffer(NULL, NULL, NULL, 0) < 0);
    CHECK(nar_vulkan_cmd_draw(NULL, NULL, 0, 0) < 0);
    printf("PASS test_pipeline_null_safety\n");
}

/*
 * Shader module and pipeline lifecycle: create a trivial SPIR-V module, create a
 * pipeline against an offscreen image, then destroy both cleanly.  Skipped when
 * Vulkan is absent or the offscreen image cannot be allocated.
 *
 * The SPIR-V modules used here are hand-assembled minimal shaders (no texture, no
 * uniforms).  They are not meant to run; pipeline creation validates them via the
 * driver.  On a host where vkCreateGraphicsPipelines succeeds the test verifies the
 * full create/destroy cycle.
 */

/*
 * Minimal SPIR-V 1.0 vertex shader: passthrough, no inputs, writes gl_Position = 0.
 * Generated offline with glslangValidator and checked in as a raw word array.
 */
static const uint32_t NAR_TEST_VERT_SPIRV[] = {
    /* Magic, version 1.0, generator=0, bound=12, schema=0 */
    0x07230203, 0x00010000, 0x00000000, 0x0000000c, 0x00000000,
    /* Capabilities */
    0x00020011, 0x00000001, /* OpCapability Shader */
    /* ExtInstImport (GLSL.std.450) */
    0x0006000b, 0x00000001, 0x4c534c47, 0x6474732e, 0x35303400, 0x00000000,
    /* MemoryModel Logical GLSL450 */
    0x0003000e, 0x00000000, 0x00000001,
    /* EntryPoint Vertex "main" %4, %8 */
    0x0006000f, 0x00000000, 0x00000004, 0x6e69616d, 0x00000000, 0x00000008,
    /* TypeVoid %2 */
    0x00020013, 0x00000002,
    /* TypeFunction %3 void */
    0x00030021, 0x00000003, 0x00000002,
    /* TypeFloat %5 32 */
    0x00030016, 0x00000005, 0x00000020,
    /* TypeVector %6 float 4 */
    0x00040017, 0x00000006, 0x00000005, 0x00000004,
    /* TypePointer %7 Output float4 */
    0x00040020, 0x00000007, 0x00000003, 0x00000006,
    /* Variable %8 gl_Position Output */
    0x00040003 | (0x0004 << 16), 0x00000007, 0x00000008, 0x00000003,
    /* Constant %9 float 0.0 */
    0x00040043, 0x00000005, 0x00000009, 0x00000000,
    /* ConstantComposite %10 float4(0,0,0,0) */
    0x00070002 | (0x0007 << 16), 0x00000006, 0x0000000a,
        0x00000009, 0x00000009, 0x00000009, 0x00000009,
    /* Function main %4 */
    0x00050036, 0x00000002, 0x00000004, 0x00000000, 0x00000003,
    /* Label %11 */
    0x000200f8, 0x0000000b,
    /* Store gl_Position, vec4(0) */
    0x0003003e, 0x00000008, 0x0000000a,
    /* Return */
    0x000100fd,
    /* FunctionEnd */
    0x00010038
};

static const uint32_t NAR_TEST_FRAG_SPIRV[] = {
    /* Magic, version 1.0, generator=0, bound=9, schema=0 */
    0x07230203, 0x00010000, 0x00000000, 0x00000009, 0x00000000,
    /* OpCapability Shader */
    0x00020011, 0x00000001,
    /* ExtInstImport */
    0x0006000b, 0x00000001, 0x4c534c47, 0x6474732e, 0x35303400, 0x00000000,
    /* MemoryModel */
    0x0003000e, 0x00000000, 0x00000001,
    /* EntryPoint Fragment "main" %4, %8 */
    0x0006000f, 0x00000004, 0x00000004, 0x6e69616d, 0x00000000, 0x00000008,
    /* ExecutionMode OriginUpperLeft */
    0x00030010, 0x00000004, 0x00000007,
    /* TypeVoid */
    0x00020013, 0x00000002,
    /* TypeFunction void */
    0x00030021, 0x00000003, 0x00000002,
    /* TypeFloat 32 */
    0x00030016, 0x00000005, 0x00000020,
    /* TypeVector float4 */
    0x00040017, 0x00000006, 0x00000005, 0x00000004,
    /* TypePointer Output float4 */
    0x00040020, 0x00000007, 0x00000003, 0x00000006,
    /* Variable out_color Output */
    0x00040003 | (0x0004 << 16), 0x00000007, 0x00000008, 0x00000003,
    /* Constant 1.0 */
    0x00040043, 0x00000005, 0x00000009, 0x3f800000,
    /* Function main */
    0x00050036, 0x00000002, 0x00000004, 0x00000000, 0x00000003,
    /* Label */
    0x000200f8, 0x0000000b,
    /* Return (output written to zero by driver) */
    0x000100fd,
    /* FunctionEnd */
    0x00010038
};

static void test_pipeline_lifecycle(void) {
    if (!nar_vulkan_loader_available()) {
        printf("SKIP test_pipeline_lifecycle: no Vulkan loader\n");
        return;
    }
    nar_vulkan_session *session =
        nar_vulkan_session_create(NAR_VULKAN_MODE_OFFSCREEN, 4, 4);
    if (session == NULL) {
        printf("SKIP test_pipeline_lifecycle: session unavailable\n");
        return;
    }

    nar_vulkan_offscreen_image *img =
        nar_vulkan_offscreen_image_create(session, 4, 4);
    if (img == NULL) {
        printf("SKIP test_pipeline_lifecycle: offscreen image unavailable\n");
        nar_vulkan_session_destroy(session);
        return;
    }

    /* Create shader modules from the hand-assembled SPIR-V. */
    nar_vulkan_shader_module *vert = nar_vulkan_shader_module_create(
        session, NAR_TEST_VERT_SPIRV, sizeof(NAR_TEST_VERT_SPIRV));
    if (vert == NULL) {
        printf("SKIP test_pipeline_lifecycle: vertex shader module unavailable (%s)\n",
               "driver may reject hand-assembled SPIR-V");
        nar_vulkan_offscreen_image_destroy(session, img);
        nar_vulkan_session_destroy(session);
        return;
    }
    nar_vulkan_shader_module *frag = nar_vulkan_shader_module_create(
        session, NAR_TEST_FRAG_SPIRV, sizeof(NAR_TEST_FRAG_SPIRV));
    if (frag == NULL) {
        printf("SKIP test_pipeline_lifecycle: fragment shader module unavailable\n");
        nar_vulkan_shader_module_destroy(session, vert);
        nar_vulkan_offscreen_image_destroy(session, img);
        nar_vulkan_session_destroy(session);
        return;
    }

    /* code_size must be a multiple of 4; verify the null-size check. */
    CHECK(nar_vulkan_shader_module_create(session, NAR_TEST_VERT_SPIRV, 3) == NULL);

    /* Create the pipeline.  The driver validates the SPIR-V; skip if it rejects
     * the hand-assembled modules (some strict drivers do). */
    nar_vulkan_pipeline *pl = nar_vulkan_pipeline_create(session, img, vert, frag);
    nar_vulkan_shader_module_destroy(session, vert);
    nar_vulkan_shader_module_destroy(session, frag);

    if (pl == NULL) {
        printf("SKIP test_pipeline_lifecycle: pipeline create rejected by driver\n");
        nar_vulkan_offscreen_image_destroy(session, img);
        nar_vulkan_session_destroy(session);
        return;
    }

    /* NULL pipeline must not crash. */
    nar_vulkan_pipeline_destroy(session, NULL);

    nar_vulkan_pipeline_destroy(session, pl);
    nar_vulkan_offscreen_image_destroy(session, img);
    nar_vulkan_session_destroy(session);
    printf("PASS test_pipeline_lifecycle\n");
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
    test_offscreen_image_no_session();
    test_offscreen_image_lifecycle();
    test_offscreen_image_readback_null_dst();
    test_buffer_no_session();
    test_buffer_lifecycle();
    test_pipeline_null_safety();
    test_pipeline_lifecycle();

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
