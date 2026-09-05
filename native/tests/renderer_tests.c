#include "native_accelerator_renderer.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

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

int main(void) {
    CHECK(nar_abi_version() == NAR_ABI_VERSION);
    CHECK((nar_capabilities() & NAR_CAP_SCENE_DATABASE) != 0);
    test_arena();
    test_scene();
    test_face_masks();
    test_parallel_batch();
    test_cache_hash();

    char name[256];
    nar_backend_name(name, sizeof(name));
    printf("Renderer backend: %s\n", name);
    printf("Vulkan loader available: %d, api=0x%08x, devices=%u\n",
           nar_vulkan_loader_available(), nar_vulkan_loader_api_version(), nar_vulkan_physical_device_count());
    puts("Native Accelerator renderer tests passed");
    return 0;
}
