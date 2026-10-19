package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererNativeApi;

import java.lang.foreign.MemorySegment;

/**
 * URD command list backed by the native Vulkan offscreen render pass.
 *
 * <p>Recording lifecycle mirrors the offscreen image ABI:
 * <ol>
 *   <li>{@link #beginPass} — calls {@code nar_vulkan_offscreen_render_begin} on the target
 *       {@link NativeVulkanUrdTexture}, caching the opaque {@code VkCommandBuffer} handle.</li>
 *   <li>{@link #setPipeline} / {@link #setVertexBuffer} / {@link #draw} — record Vulkan commands
 *       into that command buffer via the native pipeline ABI.</li>
 *   <li>{@link #endPass} — calls {@code nar_vulkan_offscreen_render_end} to close the render
 *       pass, copy the image to the readback buffer, and submit.</li>
 *   <li>{@link #submit} — a no-op here: submission happens inside {@code endPass} per the native
 *       offscreen contract.  {@link #isSubmitted()} returns {@code true} after {@link #endPass}.</li>
 * </ol>
 *
 * <p>Viewport and scissor are set to the session dimensions automatically by
 * {@code nar_vulkan_cmd_draw}; no explicit viewport/scissor state is required from callers.</p>
 */
final class NativeVulkanUrdCommandList implements UrdCommandList {
    private final RendererNativeApi api;
    private final MemorySegment session;

    /* State machine */
    private NativeVulkanUrdTexture activeTarget;
    private MemorySegment commandBuffer; /* VkCommandBuffer, valid only inside a pass */
    private NativeVulkanUrdPipeline boundPipeline;
    private boolean hasBoundVertexBuffer;
    private boolean inPass;
    private boolean submitted;
    private boolean closed;

    NativeVulkanUrdCommandList(RendererNativeApi api, MemorySegment session) {
        this.api     = api;
        this.session = session;
    }

    @Override
    public void beginPass(String name, UrdTexture colorTarget) {
        requireOpen();
        if (inPass) throw new UrdValidationException("nested render pass");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("blank pass name");
        if (colorTarget == null) throw new UrdValidationException("null color target");
        if (!(colorTarget instanceof NativeVulkanUrdTexture nvt)) {
            throw new UrdValidationException(
                    "NativeVulkanUrdCommandList.beginPass: color target must be a NativeVulkanUrdTexture");
        }
        nvt.requireOpen();

        MemorySegment cb = nvt.renderBegin();
        if (cb == null || cb.equals(MemorySegment.NULL)) {
            throw new UrdValidationException(
                    "NativeVulkanUrdCommandList.beginPass: nar_vulkan_offscreen_render_begin returned null");
        }

        activeTarget       = nvt;
        commandBuffer      = cb;
        boundPipeline      = null;
        hasBoundVertexBuffer = false;
        inPass             = true;
    }

    @Override
    public void setPipeline(UrdPipeline pipeline) {
        requireOpen();
        if (!inPass || pipeline == null) throw new UrdValidationException("pipeline must be set inside a pass");
        if (!(pipeline instanceof NativeVulkanUrdPipeline nvp)) {
            throw new UrdValidationException(
                    "NativeVulkanUrdCommandList.setPipeline: pipeline must be a NativeVulkanUrdPipeline");
        }
        nvp.requireOpen();
        // Lazily compile the native VkPipeline against the render pass of the active texture.
        MemorySegment nativePipeline = nvp.pipelineHandleFor(activeTarget.imageHandle());
        int rc = api.vulkanCmdBindPipeline(session, commandBuffer, nativePipeline);
        if (rc < 0) {
            throw new UrdValidationException(
                    "nar_vulkan_cmd_bind_pipeline failed (status " + rc + ")");
        }
        boundPipeline = nvp;
    }

    @Override
    public void setVertexBuffer(int slot, UrdBuffer buffer, long offset) {
        requireOpen();
        if (!inPass) throw new UrdValidationException("vertex buffer binding outside render pass");
        if (slot != 0) {
            throw new UrdValidationException(
                    "NativeVulkanUrdCommandList.setVertexBuffer: only binding slot 0 is supported by the native ABI");
        }
        if (buffer == null) throw new UrdValidationException("null vertex buffer");
        if (offset < 0) throw new IllegalArgumentException("offset must be >= 0");
        if (!(buffer instanceof NativeVulkanUrdBuffer nvb)) {
            throw new UrdValidationException(
                    "NativeVulkanUrdCommandList.setVertexBuffer: buffer must be a NativeVulkanUrdBuffer");
        }
        nvb.requireOpen();
        if (offset >= nvb.size()) {
            throw new UrdValidationException(
                    "vertex buffer offset " + offset + " >= buffer size " + nvb.size());
        }
        int rc = api.vulkanCmdBindVertexBuffer(session, commandBuffer, nvb.bufferHandle(), offset);
        if (rc < 0) {
            throw new UrdValidationException(
                    "nar_vulkan_cmd_bind_vertex_buffer failed (status " + rc + ")");
        }
        hasBoundVertexBuffer = true;
    }

    @Override
    public void draw(int vertexCount, int firstVertex) {
        requireOpen();
        if (!inPass) throw new UrdValidationException("draw outside render pass");
        if (boundPipeline == null) throw new UrdValidationException("draw: no pipeline bound");
        if (!hasBoundVertexBuffer) throw new UrdValidationException("draw: no vertex buffer bound");
        if (vertexCount <= 0) throw new IllegalArgumentException("vertexCount must be positive");
        if (firstVertex < 0)  throw new IllegalArgumentException("firstVertex must be >= 0");

        int rc = api.vulkanCmdDraw(session, commandBuffer, vertexCount, firstVertex);
        if (rc < 0) {
            throw new UrdValidationException("nar_vulkan_cmd_draw failed (status " + rc + ")");
        }
    }

    @Override
    public void endPass() {
        requireOpen();
        if (!inPass) throw new UrdValidationException("no active render pass");
        inPass = false;

        /* Close the render pass, copy to readback buffer, and submit. */
        int rc = activeTarget.renderEnd();
        if (rc == RendererNativeApi.OFFSCREEN_RESULT_NO_VULKAN) {
            throw new UrdValidationException("nar_vulkan_offscreen_render_end: no Vulkan loader/device");
        }
        if (rc < 0) {
            throw new UrdValidationException("nar_vulkan_offscreen_render_end failed (status " + rc + ")");
        }
        submitted  = true;
        commandBuffer = null;
    }

    @Override
    public void submit() {
        requireOpen();
        if (inPass) throw new UrdValidationException("submit inside render pass");
        /* Submission already happened in endPass per the offscreen ABI contract. */
    }

    @Override
    public boolean isSubmitted() {
        return submitted;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public void requireOpen() {
        if (closed) throw new UrdValidationException("NativeVulkanUrdCommandList is closed");
    }
}
