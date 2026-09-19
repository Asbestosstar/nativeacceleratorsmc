package com.asbestosstar.nativeaccelerator.renderer.nativeapi;

import java.lang.foreign.MemorySegment;

/** Stable Java-facing contract for the independent Vulkan renderer native ABI. */
public interface RendererNativeApi {
    int ABI_VERSION = 2;
    int INDIRECT_COMMAND_BYTES = 20;
    int SECTION_VOLUME = 4096;
    int NEIGHBOR_PLANE_BYTES = 6 * 256;

    /** Evidence: a Vulkan loader was found on this computer. */
    int EVIDENCE_VULKAN_LOADER = 1 << 0;
    /** Evidence: the loader exposed at least one usable physical device. */
    int EVIDENCE_VULKAN_DEVICE = 1 << 1;
    /** Evidence: Minecraft options.txt was located and read. */
    int EVIDENCE_MINECRAFT_OPTIONS = 1 << 2;
    /** Evidence: Minecraft options.txt selects the Vulkan backend. */
    int EVIDENCE_MINECRAFT_VULKAN = 1 << 3;
    /** Evidence: Minecraft options.txt selects a non-Vulkan backend. */
    int EVIDENCE_MINECRAFT_NON_VULKAN = 1 << 4;
    /** Evidence: an explicit user override is in force. */
    int EVIDENCE_OVERRIDE = 1 << 5;

    int ROLE_CLIENT = 0;
    int ROLE_SERVER_ONLY = 1;
    /** Let the runtime evidence decide the role. */
    int ROLE_AUTO = 2;

    int abiVersion();
    long capabilities();
    String backendName();
    boolean vulkanLoaderAvailable();
    int vulkanLoaderApiVersion();
    int vulkanPhysicalDeviceCount();
    String vulkanLoaderName();

    /** Resolved client-renderer role from runtime evidence (or an explicit override). */
    int rendererPlatformRole();
    /** True when the evidence supports selecting the client Vulkan renderer. */
    boolean rendererClientEligible();
    /** Individual evidence bits that produced the role. */
    int rendererPlatformEvidence();
    /** Human-readable evidence summary, for example: vulkan=device-ready minecraft=vulkan (client-eligible). */
    String rendererPlatformName();
    /** Force the role: {@link #ROLE_AUTO}, {@link #ROLE_CLIENT} or {@link #ROLE_SERVER_ONLY}. */
    void setRendererRoleOverride(int role);
    /** Point the Minecraft backend evidence at a specific options.txt path; null restores discovery. */
    void setMinecraftOptionsPath(String path);
    /** Path currently consulted for the Minecraft backend evidence, or an empty string when none. */
    String minecraftOptionsPath();

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

