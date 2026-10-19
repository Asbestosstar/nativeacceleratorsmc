package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererNativeApi;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/**
 * URD texture backed by a native {@code nar_vulkan_offscreen_image} handle.
 *
 * <p>Wraps the offscreen RGBA8 image ABI: create, begin-render, end-render, readback, destroy.
 * Pipeline and command-list work is not provided here; the device owns those stubs. The readback
 * path is synchronous: {@link #readback(CommandReadbackTarget)} calls
 * {@code nar_vulkan_offscreen_readback} which waits for the last submission to finish before
 * copying pixels.</p>
 *
 * <p>Pixel format is {@code VK_FORMAT_B8G8R8A8_UNORM} (tightly packed BGRA8).</p>
 */
final class NativeVulkanUrdTexture implements UrdTexture {
    private final RendererNativeApi api;
    private final MemorySegment session;
    private final MemorySegment imageHandle;
    private final int width;
    private final int height;
    private volatile boolean closed;

    NativeVulkanUrdTexture(RendererNativeApi api, MemorySegment session,
                           MemorySegment imageHandle, int width, int height) {
        this.api = api;
        this.session = session;
        this.imageHandle = imageHandle;
        this.width = width;
        this.height = height;
    }

    @Override
    public int width() {
        requireOpen();
        return width;
    }

    @Override
    public int height() {
        requireOpen();
        return height;
    }

    /**
     * Performs a synchronized readback of the last rendered offscreen frame.
     *
     * <p>This blocks until the GPU has finished and copies {@code width * height * 4} BGRA8 bytes
     * into a direct {@link ByteBuffer} delivered to the supplied {@code target}.</p>
     *
     * <p>The caller must have already performed a complete begin/end render cycle via
     * {@link #renderBegin()} and {@link #renderEnd()} before calling this, otherwise the pixel
     * content is undefined.</p>
     */
    @Override
    public void readback(CommandReadbackTarget target) {
        requireOpen();
        if (target == null) throw new IllegalArgumentException("null readback target");
        long pixelBytes = (long) width * height * 4;
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment dst = scratch.allocate(pixelBytes, 1L);
            int rc = api.vulkanOffscreenReadback(session, imageHandle, dst, pixelBytes);
            if (rc == RendererNativeApi.OFFSCREEN_RESULT_NO_VULKAN) {
                throw new UrdValidationException("vulkanOffscreenReadback: no Vulkan loader/device");
            }
            if (rc < 0) {
                throw new UrdValidationException("vulkanOffscreenReadback failed (native status " + rc + ")");
            }
            // Copy into a heap-backed ByteBuffer so the caller can retain it safely after close.
            byte[] pixels = new byte[(int) pixelBytes];
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = dst.get(ValueLayout.JAVA_BYTE, i);
            }
            target.accept(ByteBuffer.wrap(pixels));
        }
    }

    /**
     * Begin an offscreen render pass for one frame.
     *
     * @return the opaque {@code VkCommandBuffer} handle in the recording state; the caller may
     *         record additional draw commands via the native Vulkan API before calling
     *         {@link #renderEnd()}.
     */
    MemorySegment renderBegin() {
        requireOpen();
        return api.vulkanOffscreenRenderBegin(session, imageHandle);
    }

    /** Returns the opaque native image handle, for use by pipeline lazy-compilation. */
    MemorySegment imageHandle() {
        requireOpen();
        return imageHandle;
    }

    /**
     * End the current render pass and submit. Does not wait for completion; call
     * {@link #readback(CommandReadbackTarget)} to wait and retrieve pixels.
     *
     * @return native status code (0 = success, 1 = no Vulkan, negative = hard error)
     */
    int renderEnd() {
        requireOpen();
        return api.vulkanOffscreenRenderEnd(session, imageHandle);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        api.vulkanOffscreenImageDestroy(session, imageHandle);
    }

    @Override
    public void requireOpen() {
        if (closed) throw new UrdValidationException("NativeVulkanUrdTexture is closed");
    }
}
