package com.asbestosstar.nativeaccelerator.renderer.nativeapi;

import java.lang.foreign.MemorySegment;

/** Stable Java-facing contract for the independent Vulkan renderer native ABI. */
public interface RendererNativeApi {
    int ABI_VERSION = 1;
    int INDIRECT_COMMAND_BYTES = 20;
    int SECTION_VOLUME = 4096;
    int NEIGHBOR_PLANE_BYTES = 6 * 256;

    int abiVersion();
    long capabilities();
    String backendName();
    boolean vulkanLoaderAvailable();
    int vulkanLoaderApiVersion();
    int vulkanPhysicalDeviceCount();
    String vulkanLoaderName();

    MemorySegment createContext(int workerCount);
    void destroyContext(MemorySegment context);
    int contextWorkerCount(MemorySegment context);

    MemorySegment createArena(long capacity, long defaultAlignment);
    void destroyArena(MemorySegment arena);
    long arenaAllocate(MemorySegment arena, long size, long alignment);
    void arenaFree(MemorySegment arena, long offset, long size);

    MemorySegment createScene(int initialCapacity);
    void destroyScene(MemorySegment scene);
    int sceneCount(MemorySegment scene);
    void sceneUpsert(MemorySegment scene, long sectionKey,
                     float minX, float minY, float minZ,
                     float maxX, float maxY, float maxZ,
                     int firstIndex, int indexCount, int vertexOffset,
                     int firstInstance, int materialMask);
    boolean sceneRemove(MemorySegment scene, long sectionKey);

    /** Returns visible count. Throws if output capacity is too small. */
    int sceneBuildIndirect(MemorySegment scene, MemorySegment frustumPlanes24F32,
                           float cameraX, float cameraY, float cameraZ,
                           float maxDistanceSquared, int requiredMaterialMask,
                           MemorySegment outCommands, MemorySegment outSectionKeys,
                           int outputCapacity);

    int sceneExportGpu(MemorySegment scene, MemorySegment outBoundsMinVec4F32,
                       MemorySegment outBoundsMaxVec4F32, MemorySegment outDrawMeta20B,
                       MemorySegment outSectionKeys, int outputCapacity);

    void voxelFaceMasks(MemorySegment outMasks4096, MemorySegment occupancy4096,
                        MemorySegment neighborPlanesOrNull);
    void voxelFaceMasksBatch(MemorySegment context, MemorySegment outMasks,
                             MemorySegment occupancy, MemorySegment neighborPlanesOrNull,
                             int sectionCount);
}
