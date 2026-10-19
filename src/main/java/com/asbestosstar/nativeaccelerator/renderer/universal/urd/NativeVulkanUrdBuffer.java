package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererNativeApi;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/**
 * URD buffer backed by a native {@code nar_vulkan_buffer} handle.
 *
 * <p>Wraps the host-visible vertex-buffer ABI: create, upload, destroy. The native
 * {@code nar_vulkan_buffer_upload} function copies {@code size} bytes from the supplied
 * data into the buffer starting at byte 0 of the buffer; destination offsets other than
 * zero are not supported by the current native ABI and are rejected at the Java boundary.</p>
 */
final class NativeVulkanUrdBuffer implements UrdBuffer {
    private final RendererNativeApi api;
    private final MemorySegment session;
    private final MemorySegment bufferHandle;
    private final long size;
    private volatile boolean closed;

    NativeVulkanUrdBuffer(RendererNativeApi api, MemorySegment session,
                         MemorySegment bufferHandle, long size) {
        this.api = api;
        this.session = session;
        this.bufferHandle = bufferHandle;
        this.size = size;
    }

    @Override
    public long size() {
        requireOpen();
        return size;
    }

    /** Returns the opaque native buffer handle for use by command recording. */
    MemorySegment bufferHandle() {
        requireOpen();
        return bufferHandle;
    }

    @Override
    public void upload(long offset, ByteBuffer data) {
        requireOpen();
        if (offset < 0) throw new IllegalArgumentException("offset must be >= 0");
        if (data == null) throw new IllegalArgumentException("data must not be null");
        int remaining = data.remaining();
        if (remaining == 0) return;
        if (offset != 0) {
            throw new UrdValidationException(
                    "NativeVulkanUrdBuffer.upload: non-zero offset not supported by native buffer ABI");
        }
        if ((long) offset + remaining > size) {
            throw new UrdValidationException(
                    "upload exceeds buffer capacity: offset=" + offset + " length=" + remaining + " size=" + size);
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment src = scratch.allocate(remaining, 1L);
            int pos = data.position();
            for (int i = 0; i < remaining; i++) {
                src.set(ValueLayout.JAVA_BYTE, i, data.get(pos + i));
            }
            int rc = api.vulkanBufferUpload(session, bufferHandle, src, remaining);
            if (rc == RendererNativeApi.VULKAN_BUFFER_RESULT_NO_VULKAN) {
                throw new UrdValidationException("vulkanBufferUpload: no Vulkan loader/device");
            }
            if (rc < 0) {
                throw new UrdValidationException("vulkanBufferUpload failed (native status " + rc + ")");
            }
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        api.vulkanBufferDestroy(session, bufferHandle);
    }

    @Override
    public void requireOpen() {
        if (closed) throw new UrdValidationException("NativeVulkanUrdBuffer is closed");
    }
}
