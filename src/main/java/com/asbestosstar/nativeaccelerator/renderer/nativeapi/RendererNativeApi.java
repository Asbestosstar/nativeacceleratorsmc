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

    // Optional Vulkan session ABI (NAR_VULKAN_ABI_VERSION = 1).
    // These symbols are absent in library builds that pre-date the session ABI;
    // callers must check vulkanSessionSupported() before invoking any of them.
    int VULKAN_ABI_VERSION = 1;
    int VULKAN_MODE_AUTO      = 0;
    int VULKAN_MODE_OFFSCREEN = 1;
    int VULKAN_MODE_PRESENT   = 2;
    int VULKAN_TIER_UNAVAILABLE = 0;
    int VULKAN_TIER_BASIC       = 1;
    int VULKAN_TIER_PRESENT     = 2;
    int VULKAN_TIER_ENHANCED    = 3;
    long VULKAN_FEATURE_CLASSIC_RENDER_PASS = 1L;
    long VULKAN_FEATURE_CPU_CULL             = 1L << 1;
    long VULKAN_FEATURE_SWAPCHAIN            = 1L << 2;
    long VULKAN_FEATURE_DYNAMIC_RENDERING    = 1L << 3;
    long VULKAN_FEATURE_SYNCHRONIZATION2     = 1L << 4;
    long VULKAN_FEATURE_PUSH_DESCRIPTOR      = 1L << 5;
    long VULKAN_FEATURE_VERTEX_DIVISOR       = 1L << 6;
    long VULKAN_FEATURE_GPU_CULL             = 1L << 7;

    int VULKAN_WSI_ABI_VERSION = 1;
    int VULKAN_WSI_PLATFORM_XLIB = 1;
    int VULKAN_WSI_PLATFORM_XCB = 2;
    int VULKAN_WSI_PLATFORM_WAYLAND = 3;
    int VULKAN_WSI_PLATFORM_WIN32 = 4;
    int VULKAN_WSI_PLATFORM_COCOA = 5;
    int VULKAN_WSI_PLATFORM_SDL = 6;
    int VULKAN_WSI_STATUS_OK = 0;
    int VULKAN_WSI_STATUS_UNAVAILABLE = 1;
    int VULKAN_WSI_STATUS_BLOCKED = 2;
    int VULKAN_WSI_STATUS_OUT_OF_DATE = 3;
    int VULKAN_WSI_STATUS_SUBOPTIMAL = 4;
    int VULKAN_WSI_STATUS_SURFACE_LOST = 5;
    int VULKAN_WSI_STATUS_INVALID = 6;
    int VULKAN_WSI_STATUS_ERROR = 7;
    int VULKAN_WSI_STATUS_NOT_READY = 8;
    int VULKAN_WSI_STATUS_TIMEOUT = 9;
    int VULKAN_WSI_STATUS_PAUSED = 10;
    int VULKAN_WSI_STATUS_DEVICE_LOST = 11;
    int VULKAN_WSI_STATUS_UNSUPPORTED = 12;

    /** True when the optional session ABI symbols are present in this library build. */
    default boolean vulkanSessionSupported() { return false; }

    /**
     * Create an offscreen or present-capable Vulkan session.
     * {@code mode} is one of {@link #VULKAN_MODE_OFFSCREEN} / {@link #VULKAN_MODE_PRESENT}.
     * Returns a non-null opaque handle; throws when creation fails.
     */
    default MemorySegment vulkanSessionCreate(int mode, int width, int height) {
        throw new UnsupportedOperationException("Vulkan session ABI not available in this library build");
    }
    /** Destroy a session previously obtained from {@link #vulkanSessionCreate}. */
    default void vulkanSessionDestroy(MemorySegment session) {
        throw new UnsupportedOperationException("Vulkan session ABI not available in this library build");
    }
    /** Capability tier of the session (one of {@link #VULKAN_TIER_BASIC} etc.). */
    default int vulkanSessionTier(MemorySegment session) {
        throw new UnsupportedOperationException("Vulkan session ABI not available in this library build");
    }
    /** Feature bitmask of the session (NAR_VULKAN_FEATURE_* bits). */
    default long vulkanSessionFeatures(MemorySegment session) {
        throw new UnsupportedOperationException("Vulkan session ABI not available in this library build");
    }
    /** Last diagnostic message from the session, or a static error string if the session is null. */
    default String vulkanSessionLastError(MemorySegment session) {
        throw new UnsupportedOperationException("Vulkan session ABI not available in this library build");
    }

    /** True when the additive native WSI symbols are available. */
    default boolean vulkanWsiSupported() { return false; }
    /** Create a native-owned present session. Native handles are borrowed only for creation. */
    default MemorySegment vulkanSessionCreatePresent(int platform, long nativeDisplay, long nativeWindow,
                                                     int flags, int width, int height) {
        throw new UnsupportedOperationException("Vulkan WSI ABI not available in this library build");
    }
    default int vulkanWsiSupported(int platform) {
        throw new UnsupportedOperationException("Vulkan WSI ABI not available in this library build");
    }
    default MemorySegment vulkanSwapchainCreate(MemorySegment session, int width, int height) {
        throw new UnsupportedOperationException("Vulkan WSI ABI not available in this library build");
    }
    default void vulkanSwapchainDestroy(MemorySegment session, MemorySegment swapchain) {
        throw new UnsupportedOperationException("Vulkan WSI ABI not available in this library build");
    }
    default int vulkanSwapchainRecreate(MemorySegment session, MemorySegment swapchain, int width, int height) {
        throw new UnsupportedOperationException("Vulkan WSI ABI not available in this library build");
    }
    default int vulkanSwapchainAcquire(MemorySegment session, MemorySegment swapchain,
                                       MemorySegment outImageIndex) {
        throw new UnsupportedOperationException("Vulkan WSI ABI not available in this library build");
    }
    default int vulkanSwapchainPresent(MemorySegment session, MemorySegment swapchain, int imageIndex) {
        throw new UnsupportedOperationException("Vulkan WSI ABI not available in this library build");
    }

    /** Path of the selected Mesa tree on Unix-family hosts, or an empty string. */
    default String vulkanMesaPrefix() { return ""; }
    /** Path of the Mesa Vulkan loader inside the selected tree, or an empty string. */
    default String vulkanMesaLoader() { return ""; }

    // Offscreen RGBA8 image/render/readback ABI (optional — check vulkanOffscreenSupported() first).
    // These symbols are absent in library builds that pre-date the offscreen ABI.
    int OFFSCREEN_RESULT_OK = 0;
    /** Returned when no Vulkan loader or device is present. */
    int OFFSCREEN_RESULT_NO_VULKAN = 1;

    /** True when the optional offscreen image/render/readback ABI is present in this library build. */
    default boolean vulkanOffscreenSupported() { return false; }

    /**
     * Allocate an offscreen RGBA8 image of the given dimensions backed by a device-local
     * {@code VkImage} and a host-visible readback buffer. Returns a non-null opaque handle;
     * throws when creation fails.
     */
    default MemorySegment vulkanOffscreenImageCreate(MemorySegment session, int width, int height) {
        throw new UnsupportedOperationException("Vulkan offscreen ABI not available in this library build");
    }
    /** Destroy an offscreen image previously obtained from {@link #vulkanOffscreenImageCreate}. */
    default void vulkanOffscreenImageDestroy(MemorySegment session, MemorySegment image) {
        throw new UnsupportedOperationException("Vulkan offscreen ABI not available in this library build");
    }
    /**
     * Begin a render pass for one offscreen frame. On success returns the {@code VkCommandBuffer}
     * in the recording state with the render pass already started. The caller may cast it to
     * record additional draw commands before calling {@link #vulkanOffscreenRenderEnd}. Throws on
     * hard error.
     */
    default MemorySegment vulkanOffscreenRenderBegin(MemorySegment session, MemorySegment image) {
        throw new UnsupportedOperationException("Vulkan offscreen ABI not available in this library build");
    }
    /**
     * End the render pass, copy the image to the readback buffer, and submit. Does NOT wait for
     * completion; call {@link #vulkanOffscreenReadback} for that.
     * Returns 0 on success, negative on hard error, {@link #OFFSCREEN_RESULT_NO_VULKAN} when
     * Vulkan is unavailable.
     */
    default int vulkanOffscreenRenderEnd(MemorySegment session, MemorySegment image) {
        throw new UnsupportedOperationException("Vulkan offscreen ABI not available in this library build");
    }
    /**
     * Wait for the last submission to finish, then copy {@code width*height*4} BGRA8 pixels into
     * {@code dst}. Capacity must be {@code >= width * height * 4}. Pixel format is
     * {@code VK_FORMAT_B8G8R8A8_UNORM}.
     * Returns 0 on success, negative on hard error, {@link #OFFSCREEN_RESULT_NO_VULKAN} when
     * Vulkan is unavailable.
     */
    default int vulkanOffscreenReadback(MemorySegment session, MemorySegment image,
                                        MemorySegment dst, long capacity) {
        throw new UnsupportedOperationException("Vulkan offscreen ABI not available in this library build");
    }

    // Optional Vulkan buffer resource ABI (optional — check vulkanBufferSupported() first).
    // These symbols are absent in library builds that pre-date the buffer ABI.
    int VULKAN_BUFFER_RESULT_OK = 0;
    /** Returned when no Vulkan loader or device is present. */
    int VULKAN_BUFFER_RESULT_NO_VULKAN = 1;

    /** True when the optional buffer resource ABI is present in this library build. */
    default boolean vulkanBufferSupported() { return false; }

    /**
     * Create a host-visible vertex buffer of the given byte size. Returns a non-null
     * opaque handle; throws when creation fails.
     */
    default MemorySegment vulkanBufferCreate(MemorySegment session, long size) {
        throw new UnsupportedOperationException("Vulkan buffer ABI not available in this library build");
    }
    /** Destroy a buffer previously obtained from {@link #vulkanBufferCreate}. Safe with null. */
    default void vulkanBufferDestroy(MemorySegment session, MemorySegment buffer) {
        throw new UnsupportedOperationException("Vulkan buffer ABI not available in this library build");
    }
    /**
     * Copy host data into the buffer starting at byte 0. {@code size} must be &le; the buffer
     * capacity. Returns 0 on success, negative on hard error,
     * {@link #VULKAN_BUFFER_RESULT_NO_VULKAN} when Vulkan is unavailable.
     */
    default int vulkanBufferUpload(MemorySegment session, MemorySegment buffer,
                                   MemorySegment data, long size) {
        throw new UnsupportedOperationException("Vulkan buffer ABI not available in this library build");
    }
    /** Return the buffer capacity in bytes, or 0 when the handle is null. */
    default long vulkanBufferSize(MemorySegment buffer) {
        throw new UnsupportedOperationException("Vulkan buffer ABI not available in this library build");
    }

    // Optional shader module + graphics pipeline + command recording ABI.
    // Check vulkanPipelineSupported() before invoking any of these.
    int VULKAN_PIPELINE_RESULT_OK        = 0;
    /** Returned when no Vulkan loader or device is present. */
    int VULKAN_PIPELINE_RESULT_NO_VULKAN = 1;

    /** True when the shader-module/pipeline/command-recording ABI is present in this library build. */
    default boolean vulkanPipelineSupported() { return false; }

    /**
     * Create a shader module from SPIR-V bytecode. {@code code} must be a direct native buffer
     * whose size is a multiple of 4. Returns a non-null opaque handle; throws when creation fails.
     */
    default MemorySegment vulkanShaderModuleCreate(MemorySegment session, MemorySegment code, long codeSize) {
        throw new UnsupportedOperationException("Vulkan pipeline ABI not available in this library build");
    }
    /** Destroy a shader module previously obtained from {@link #vulkanShaderModuleCreate}. */
    default void vulkanShaderModuleDestroy(MemorySegment session, MemorySegment module) {
        throw new UnsupportedOperationException("Vulkan pipeline ABI not available in this library build");
    }

    /**
     * Create a graphics pipeline bound to the render pass of {@code image}. Uses a fixed vertex
     * layout: binding 0, stride 20, two attributes — vec2 position at offset 0 / location 0,
     * vec3 color at offset 8 / location 1. Viewport and scissor are dynamic.
     * Returns a non-null opaque handle; throws when creation fails.
     */
    default MemorySegment vulkanPipelineCreate(MemorySegment session, MemorySegment image,
                                               MemorySegment vertexShader,
                                               MemorySegment fragmentShader) {
        throw new UnsupportedOperationException("Vulkan pipeline ABI not available in this library build");
    }
    /** Destroy a pipeline previously obtained from {@link #vulkanPipelineCreate}. */
    default void vulkanPipelineDestroy(MemorySegment session, MemorySegment pipeline) {
        throw new UnsupportedOperationException("Vulkan pipeline ABI not available in this library build");
    }

    /**
     * Record vkCmdBindPipeline into {@code commandBuffer} (the handle returned by
     * {@link #vulkanOffscreenRenderBegin}). Returns 0 on success, negative on error.
     */
    default int vulkanCmdBindPipeline(MemorySegment session, MemorySegment commandBuffer,
                                      MemorySegment pipeline) {
        throw new UnsupportedOperationException("Vulkan pipeline ABI not available in this library build");
    }
    /**
     * Record vkCmdBindVertexBuffers (binding 0) into {@code commandBuffer}.
     * {@code offset} is the byte offset into the buffer. Returns 0 on success, negative on error.
     */
    default int vulkanCmdBindVertexBuffer(MemorySegment session, MemorySegment commandBuffer,
                                          MemorySegment buffer, long offset) {
        throw new UnsupportedOperationException("Vulkan pipeline ABI not available in this library build");
    }
    /**
     * Record vkCmdSetViewport, vkCmdSetScissor (from session dimensions), and vkCmdDraw into
     * {@code commandBuffer}. {@code firstVertex} is the starting vertex index.
     * Returns 0 on success, negative on error.
     */
    default int vulkanCmdDraw(MemorySegment session, MemorySegment commandBuffer,
                              int vertexCount, int firstVertex) {
        throw new UnsupportedOperationException("Vulkan pipeline ABI not available in this library build");
    }

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

