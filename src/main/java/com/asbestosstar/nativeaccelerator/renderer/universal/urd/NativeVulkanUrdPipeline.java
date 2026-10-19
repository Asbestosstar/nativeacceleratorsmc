package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererNativeApi;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderModule;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ConcurrentHashMap;

/**
 * URD pipeline backed by a native {@code nar_vulkan_pipeline} handle.
 *
 * <p>Pipeline creation in Vulkan requires a {@code VkRenderPass}, which is owned by the
 * offscreen image and not known when {@link UrdDevice#createPipeline} is called. This class
 * therefore uses lazy initialization: the first call to {@link #pipelineHandleFor(MemorySegment)}
 * with a given offscreen image handle creates the native {@code VkPipeline} and caches it by
 * image handle identity. Subsequent calls with the same image return the cached handle.
 *
 * <p>The vertex layout is fixed by the native ABI: binding 0, stride 20,
 * location 0 = vec2 position at offset 0, location 1 = vec3 color at offset 8.
 * Viewport and scissor are dynamic.</p>
 *
 * <p>Shader modules are created and immediately destroyed after each pipeline compilation;
 * the driver retains the SPIR-V through the pipeline object itself.</p>
 */
final class NativeVulkanUrdPipeline implements UrdPipeline {
    private final RendererNativeApi api;
    private final MemorySegment session;
    private final ShaderModule vertexShaderModule;
    private final ShaderModule fragmentShaderModule;
    /** Cache: offscreen-image-handle identity → VkPipeline handle. */
    private final ConcurrentHashMap<Long, MemorySegment> pipelineCache = new ConcurrentHashMap<>();
    private volatile boolean closed;

    NativeVulkanUrdPipeline(RendererNativeApi api, MemorySegment session,
                             ShaderModule vertexShaderModule, ShaderModule fragmentShaderModule) {
        this.api                  = api;
        this.session              = session;
        this.vertexShaderModule   = vertexShaderModule;
        this.fragmentShaderModule = fragmentShaderModule;
    }

    /**
     * Returns (or lazily creates) the native {@code VkPipeline} handle compatible with the
     * render pass of the given offscreen image.
     *
     * @param imageHandle the offscreen image handle whose render pass the pipeline targets
     */
    MemorySegment pipelineHandleFor(MemorySegment imageHandle) {
        requireOpen();
        if (!api.vulkanPipelineSupported()) {
            throw new UrdValidationException(
                    "NativeVulkanUrdPipeline: pipeline ABI not present in this library build");
        }
        // Use the raw address as the cache key — same image handle → same render pass.
        long key = imageHandle.address();
        return pipelineCache.computeIfAbsent(key, k -> compilePipeline(imageHandle));
    }

    private MemorySegment compilePipeline(MemorySegment imageHandle) {
        java.nio.ByteBuffer vsSpirv = vertexShaderModule.spirvWords();
        java.nio.ByteBuffer fsSpirv = fragmentShaderModule.spirvWords();
        if (vsSpirv == null || !vsSpirv.hasRemaining()) {
            throw new UrdValidationException(
                    "NativeVulkanUrdPipeline: vertex shader has no SPIR-V binary");
        }
        if (fsSpirv == null || !fsSpirv.hasRemaining()) {
            throw new UrdValidationException(
                    "NativeVulkanUrdPipeline: fragment shader has no SPIR-V binary");
        }

        MemorySegment vsHandle;
        MemorySegment fsHandle;
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment vsBuf = copySpirv(scratch, vsSpirv);
            MemorySegment fsBuf = copySpirv(scratch, fsSpirv);
            vsHandle = api.vulkanShaderModuleCreate(session, vsBuf, vsBuf.byteSize());
            try {
                fsHandle = api.vulkanShaderModuleCreate(session, fsBuf, fsBuf.byteSize());
            } catch (RuntimeException | Error e) {
                api.vulkanShaderModuleDestroy(session, vsHandle);
                throw e;
            }
        }

        try {
            return api.vulkanPipelineCreate(session, imageHandle, vsHandle, fsHandle);
        } finally {
            // Shader modules are not needed after pipeline creation.
            api.vulkanShaderModuleDestroy(session, vsHandle);
            api.vulkanShaderModuleDestroy(session, fsHandle);
        }
    }

    @Override
    public ShaderModule vertexShader() {
        requireOpen();
        return vertexShaderModule;
    }

    @Override
    public ShaderModule fragmentShader() {
        requireOpen();
        return fragmentShaderModule;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (MemorySegment handle : pipelineCache.values()) {
            try {
                api.vulkanPipelineDestroy(session, handle);
            } catch (RuntimeException ignored) {
                // Best-effort cleanup; one failure must not block the rest.
            }
        }
        pipelineCache.clear();
    }

    @Override
    public void requireOpen() {
        if (closed) throw new UrdValidationException("NativeVulkanUrdPipeline is closed");
    }

    /** Copy SPIR-V bytes into a native segment aligned to 4 bytes. */
    private static MemorySegment copySpirv(Arena arena, java.nio.ByteBuffer spirv) {
        java.nio.ByteBuffer view = spirv.duplicate().order(spirv.order());
        int bytes = view.remaining();
        if (bytes % 4 != 0) {
            throw new UrdValidationException(
                    "SPIR-V binary size " + bytes + " is not a multiple of 4");
        }
        MemorySegment seg = arena.allocate(bytes, 4L);
        byte[] tmp = new byte[bytes];
        view.get(tmp);
        for (int i = 0; i < bytes; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, tmp[i]);
        }
        return seg;
    }
}
