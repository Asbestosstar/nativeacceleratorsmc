#include "native_accelerator_renderer.h"

#include <stddef.h>
#include <stdint.h>

enum { SECTION_VOLUME = 4096, PLANE_SIZE = 256 };

static size_t index_xyz(int x, int y, int z) {
    return (size_t)(x | (z << 4) | (y << 8));
}

static int occupied_internal(const uint8_t *occupancy, int x, int y, int z) {
    return occupancy[index_xyz(x, y, z)] != 0;
}

static int boundary_occupied(const uint8_t *planes, int face, int x, int y, int z) {
    if (planes == NULL) return 0;
    const uint8_t *plane = planes + (size_t)face * PLANE_SIZE;
    switch (face) {
        case 0: /* -X */
        case 1: /* +X */
            return plane[z | (y << 4)] != 0;
        case 2: /* -Y */
        case 3: /* +Y */
            return plane[x | (z << 4)] != 0;
        case 4: /* -Z */
        case 5: /* +Z */
            return plane[x | (y << 4)] != 0;
        default:
            return 0;
    }
}

int32_t nar_voxel_face_masks(uint8_t *out_masks,
                             const uint8_t *occupancy,
                             const uint8_t *neighbor_planes) {
    if (out_masks == NULL || occupancy == NULL) return -1;
    for (int y = 0; y < 16; ++y) {
        for (int z = 0; z < 16; ++z) {
            for (int x = 0; x < 16; ++x) {
                size_t index = index_xyz(x, y, z);
                if (occupancy[index] == 0) {
                    out_masks[index] = 0;
                    continue;
                }
                uint8_t mask = 0;
                if (x == 0 ? !boundary_occupied(neighbor_planes, 0, x, y, z)
                           : !occupied_internal(occupancy, x - 1, y, z)) mask |= NAR_FACE_NEG_X;
                if (x == 15 ? !boundary_occupied(neighbor_planes, 1, x, y, z)
                            : !occupied_internal(occupancy, x + 1, y, z)) mask |= NAR_FACE_POS_X;
                if (y == 0 ? !boundary_occupied(neighbor_planes, 2, x, y, z)
                           : !occupied_internal(occupancy, x, y - 1, z)) mask |= NAR_FACE_NEG_Y;
                if (y == 15 ? !boundary_occupied(neighbor_planes, 3, x, y, z)
                            : !occupied_internal(occupancy, x, y + 1, z)) mask |= NAR_FACE_POS_Y;
                if (z == 0 ? !boundary_occupied(neighbor_planes, 4, x, y, z)
                           : !occupied_internal(occupancy, x, y, z - 1)) mask |= NAR_FACE_NEG_Z;
                if (z == 15 ? !boundary_occupied(neighbor_planes, 5, x, y, z)
                            : !occupied_internal(occupancy, x, y, z + 1)) mask |= NAR_FACE_POS_Z;
                out_masks[index] = mask;
            }
        }
    }
    return 0;
}

typedef struct nar_face_batch {
    uint8_t *out_masks;
    const uint8_t *occupancy;
    const uint8_t *neighbor_planes;
} nar_face_batch;

static void build_one(void *userdata, uint32_t index) {
    nar_face_batch *batch = (nar_face_batch *)userdata;
    uint8_t *out = batch->out_masks + (size_t)index * SECTION_VOLUME;
    const uint8_t *occupancy = batch->occupancy + (size_t)index * SECTION_VOLUME;
    const uint8_t *neighbors = batch->neighbor_planes == NULL ? NULL : batch->neighbor_planes + (size_t)index * 6u * PLANE_SIZE;
    (void)nar_voxel_face_masks(out, occupancy, neighbors);
}

int32_t nar_voxel_face_masks_batch(nar_context *context,
                                   uint8_t *out_masks,
                                   const uint8_t *occupancy,
                                   const uint8_t *neighbor_planes,
                                   uint32_t section_count) {
    if ((section_count != 0 && (out_masks == NULL || occupancy == NULL))) return -1;
    nar_face_batch batch = { out_masks, occupancy, neighbor_planes };
    return nar_parallel_for(context, section_count, build_one, &batch);
}
