package com.asbestosstar.nativeaccelerator.renderer.nativeapi;

import java.util.ArrayList;
import java.util.List;

public final class RendererCapabilities {
    private RendererCapabilities() {}

    public static final long VULKAN_LOADER       = 1L << 0;
    public static final long SCENE_DATABASE      = 1L << 1;
    public static final long ARENA_ALLOCATOR     = 1L << 2;
    public static final long CPU_FRUSTUM_CULL    = 1L << 3;
    public static final long INDIRECT_COMMANDS   = 1L << 4;
    public static final long VOXEL_FACE_MASKS    = 1L << 5;
    public static final long NATIVE_WORKERS      = 1L << 6;
    public static final long PIPELINE_CACHE_IO   = 1L << 7;
    public static final long VULKAN_DEVICE       = 1L << 8;

    public static List<String> names(long mask) {
        List<String> out = new ArrayList<>();
        add(out, mask, VULKAN_LOADER, "vulkan-loader");
        add(out, mask, SCENE_DATABASE, "scene-database");
        add(out, mask, ARENA_ALLOCATOR, "arena-allocator");
        add(out, mask, CPU_FRUSTUM_CULL, "cpu-frustum-cull");
        add(out, mask, INDIRECT_COMMANDS, "indirect-commands");
        add(out, mask, VOXEL_FACE_MASKS, "voxel-face-masks");
        add(out, mask, NATIVE_WORKERS, "native-workers");
        add(out, mask, PIPELINE_CACHE_IO, "pipeline-cache-io");
        add(out, mask, VULKAN_DEVICE, "vulkan-device");
        return List.copyOf(out);
    }

    private static void add(List<String> out, long mask, long bit, String name) {
        if ((mask & bit) != 0) out.add(name);
    }
}
