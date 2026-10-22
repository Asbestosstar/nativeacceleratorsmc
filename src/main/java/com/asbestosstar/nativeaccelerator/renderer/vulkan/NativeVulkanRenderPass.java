package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearRect;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkViewport;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Live Vulkan render-pass encoder.
 *
 * <p>One instance is created for each {@code createRenderPass} call. It opens a
 * {@code vkCmdBeginRenderPass} immediately and records pipeline binds, vertex/index binds,
 * scissor, push constants, and draw commands into the caller-supplied command buffer.
 * {@code vkCmdEndRenderPass} is issued when the paired {@code submitRenderPass} call arrives.
 * The transient {@code VkFramebuffer} is destroyed after the pass ends.</p>
 *
 * <p>The backend capability gate remains hard-false in {@link NativeVulkanCapabilityProbe};
 * this class is only reachable once the adapter is fully wired. All operations that remain
 * unimplemented throw {@link UnsupportedOperationException} so they fail loudly rather than
 * silently corrupting GPU state.</p>
 */
final class NativeVulkanRenderPass implements RenderPassBackend {

    // Maximum vertex-buffer binding slots honoured by this encoder.
    private static final int MAX_VERTEX_SLOTS = 16;

    private final NativeVulkanDeviceState state;
    private final NativeVulkanCommandState recordingState;
    private final NativeVulkanFrameSubmission frame;
    private final VkCommandBuffer commandBuffer;
    private final NativeVulkanDeviceBootstrap bootstrap;
    private final int renderAreaWidth;
    private final int renderAreaHeight;
    private final int renderAreaX;
    private final int renderAreaY;
    private final int renderAreaExtentWidth;
    private final int renderAreaExtentHeight;
    // Attachment views captured at construction; VkFramebuffer is created lazily at first setPipeline
    // so it uses the pipeline's compatible VkRenderPass handle.
    private final NativeVulkanTextureView deferredColorView;
    private final NativeVulkanTextureView deferredDepthView;
    private final int descriptorColorCount;
    private final java.util.List<java.util.Optional<org.joml.Vector4fc>> colorClearValues;
    private final java.util.OptionalDouble depthClearValue;

    // Per-pass mutable draw state -----------------------------------------------------------

    private NativeVulkanRenderPipeline pipeline;
    private int appliedScissorX, appliedScissorY, appliedScissorW, appliedScissorH;
    private boolean scissorApplied;
    private int pendingScissorX, pendingScissorY, pendingScissorW, pendingScissorH;
    private boolean scissorDirty;
    private boolean indexBufferBound;
    private NativeVulkanGpuBuffer indexBuffer;
    private IndexType boundIndexType;

    // Closing guard
    private boolean ended;

    /**
     * Opens a new render pass, creating a transient {@code VkFramebuffer} from the descriptor's
     * color attachment and recording {@code vkCmdBeginRenderPass} into {@code commandBuffer}.
     *
     * @param bootstrap       live device bootstrap — owns the Vulkan device handle
     * @param state           device lifecycle state
     * @param recordingState  command-list phase tracker; must be in IN_PASS after this call
     * @param commandBuffer   recording command buffer; must already be in the recording state
     * @param descriptor      pass descriptor from RenderPearl — must carry a color attachment
     */
    NativeVulkanRenderPass(NativeVulkanDeviceBootstrap bootstrap,
                           NativeVulkanDeviceState state,
                           NativeVulkanCommandState recordingState,
                           NativeVulkanFrameSubmission frame,
                           VkCommandBuffer commandBuffer,
                           RenderPassDescriptor descriptor) {
        this.bootstrap = java.util.Objects.requireNonNull(bootstrap, "bootstrap");
        this.state = java.util.Objects.requireNonNull(state, "state");
        this.recordingState = java.util.Objects.requireNonNull(recordingState, "recordingState");
        this.frame = java.util.Objects.requireNonNull(frame, "frame");
        this.commandBuffer = java.util.Objects.requireNonNull(commandBuffer, "commandBuffer");
        java.util.Objects.requireNonNull(descriptor, "descriptor");

        // Resolve the color attachment's VkImageView. The compatible VkRenderPass comes from the
        // pipeline bound first inside this pass (NativeVulkanRenderPipeline compiles its own
        // VkRenderPass). We defer VkFramebuffer creation and vkCmdBeginRenderPass to the first
        // setPipeline call so we always use the correct VkRenderPass handle.

        // colorAttachments() returns List<Attachment<Optional<Vector4fc>>>.
        if (descriptor.colorAttachments().isEmpty()) {
            throw new IllegalArgumentException("NativeVulkanRenderPass: descriptor has no color attachment");
        }
        if (descriptor.colorAttachments().size() != 1) {
            throw new UnsupportedOperationException(
                    "NativeVulkanRenderPass supports exactly one color attachment");
        }
        GpuTextureView rawColorView = descriptor.colorAttachments().get(0).textureView();
        if (!(rawColorView instanceof NativeVulkanTextureView colorView)) {
            throw new IllegalArgumentException("NativeVulkanRenderPass: color attachment view is not a NativeVulkanTextureView");
        }

        if ((colorView.texture().usage() & com.mojang.renderpearl.api.textures.GpuTexture.USAGE_RENDER_ATTACHMENT) == 0) {
            throw new IllegalArgumentException("Color texture lacks render-attachment usage");
        }
        if (colorView.baseMipLevel() >= colorView.texture().getMipLevels() || colorView.layerCount() != 1) {
            throw new UnsupportedOperationException("Vulkan render passes require a single-layer color view");
        }

        // Record render-area dimensions for scissor restoration on disableScissor().
        this.renderAreaWidth  = colorView.getWidth(0);
        this.renderAreaHeight = colorView.getHeight(0);
        var renderArea = descriptor.renderArea();
        this.renderAreaX = renderArea.x();
        this.renderAreaY = renderArea.y();
        this.renderAreaExtentWidth = renderArea.width();
        this.renderAreaExtentHeight = renderArea.height();
        if (renderArea.x() < 0 || renderArea.y() < 0 || renderArea.width() <= 0 || renderArea.height() <= 0
                || (long) renderArea.x() + renderArea.width() > renderAreaWidth
                || (long) renderArea.y() + renderArea.height() > renderAreaHeight) {
            throw new IllegalArgumentException("Render area exceeds color attachment bounds");
        }

        this.deferredColorView = colorView;
        this.descriptorColorCount = descriptor.colorAttachments().size();
        this.colorClearValues = descriptor.colorAttachments().stream()
                .map(attachment -> attachment == null
                        ? Optional.<org.joml.Vector4fc>empty() : attachment.clearValue())
                .toList();
        var depthAttachment = descriptor.depthAttachment();
        this.depthClearValue = depthAttachment == null ? java.util.OptionalDouble.empty() : depthAttachment.clearValue();
        if (depthAttachment != null && !(depthAttachment.textureView() instanceof NativeVulkanTextureView)) {
            throw new IllegalArgumentException("NativeVulkanRenderPass: depth attachment view is not a NativeVulkanTextureView");
        }
        this.deferredDepthView = depthAttachment == null ? null
                : (NativeVulkanTextureView) depthAttachment.textureView();
        if (deferredDepthView != null && deferredDepthView.texture().getFormat() != GpuFormat.D32_FLOAT) {
            throw new UnsupportedOperationException(
                    "NativeVulkanRenderPass currently supports only D32_FLOAT depth attachments");
        }
        if (deferredDepthView != null
                && ((deferredDepthView.texture().usage() & com.mojang.renderpearl.api.textures.GpuTexture.USAGE_RENDER_ATTACHMENT) == 0
                || deferredDepthView.layerCount() != 1)) {
            throw new IllegalArgumentException("Depth attachment requires single-layer render-attachment usage");
        }
        if (descriptor.colorAttachments().get(0) == null) {
            throw new UnsupportedOperationException("NativeVulkanRenderPass does not support unused color attachments");
        }
        if (descriptor.colorAttachments().get(0).clearValue() == null) {
            throw new IllegalArgumentException("NativeVulkanRenderPass: color clear value wrapper is null");
        }
        if (depthAttachment != null && depthAttachment.clearValue() == null) {
            throw new IllegalArgumentException("NativeVulkanRenderPass: depth clear value wrapper is null");
        }

        this.pendingScissorX = renderAreaX;
        this.pendingScissorY = renderAreaY;
        this.pendingScissorW = renderAreaExtentWidth;
        this.pendingScissorH = renderAreaExtentHeight;
        this.scissorDirty = false;
    }

    // Attachment views survive until the VkFramebuffer is created on first setPipeline.
    private long deferredFramebuffer = VK10.VK_NULL_HANDLE;
    private long clearOnlyRenderPass = VK10.VK_NULL_HANDLE;
    private boolean renderPassBegun;

    // -----------------------------------------------------------------------------------------
    // RenderPassBackend
    // -----------------------------------------------------------------------------------------

    @Override
    public void pushDebugGroup(Supplier<String> label) {
        // Honour debug groups when available; swallow gracefully if the extension is absent.
        // vkCmdBeginDebugUtilsLabelEXT is optional; we record without it for now.
        requireOpen();
    }

    @Override
    public void popDebugGroup() {
        requireOpen();
    }

    @Override
    public synchronized void setPipeline(BackendRenderPipeline value) {
        requireOpen();
        if (!(value instanceof NativeVulkanRenderPipeline vkPipeline)) {
            throw new IllegalArgumentException("Foreign pipeline bound to NativeVulkanRenderPass");
        }
        recordingState.requireCanSetPipeline(value);
        if (vkPipeline.colorTargetCount() != 1 || descriptorColorCount != 1) {
            throw new UnsupportedOperationException(
                    "Native Vulkan currently supports one color target per render pass");
        }
        if (vkPipeline.hasDepthStencil() != (deferredDepthView != null)) {
            throw new IllegalArgumentException("Pipeline depth/stencil state does not match render pass depth attachment");
        }
        if (deferredColorView.texture().getFormat() != vkPipeline.colorTargetFormat(0)) {
            throw new IllegalArgumentException("Pipeline color target format does not match render pass attachment format");
        }
        if (deferredDepthView != null
                && deferredDepthView.texture().getFormat() != vkPipeline.depthFormat()) {
            throw new IllegalArgumentException("Pipeline depth format does not match render pass attachment format");
        }
        if (deferredColorView.baseMipLevel() >= deferredColorView.texture().getMipLevels()
                || (long) deferredColorView.baseMipLevel() >= deferredColorView.texture().getMipLevels()) {
            throw new IllegalStateException("Invalid color attachment mip view");
        }
        if (vkPipeline.uniforms().size() > 0) {
            throw new UnsupportedOperationException("Vulkan uniform descriptors are not implemented");
        }
        if (colorClearValues.get(0).isEmpty()
                && !deferredColorView.texture().subresourcesInitialized(
                deferredColorView.baseMipLevel(), 0, deferredColorView.layerCount())) {
            throw new UnsupportedOperationException(
                    "Render-pass LOAD requires initialized color contents; clear the attachment before drawing");
        }
        if (deferredDepthView != null && depthClearValue.isEmpty()
                && !deferredDepthView.texture().subresourcesInitialized(
                deferredDepthView.baseMipLevel(), 0, deferredDepthView.layerCount())) {
            throw new UnsupportedOperationException(
                    "Render-pass LOAD requires initialized depth contents; clear the attachment before drawing");
        }

        // On the first pipeline bind, create the VkFramebuffer and start the render pass.
        if (!renderPassBegun) {
            beginRenderPassNow(vkPipeline.renderPassHandle());
        }

        // Bind the Vulkan pipeline.
        VK10.vkCmdBindPipeline(commandBuffer,
                VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, vkPipeline.pipelineHandle());

        pipeline = vkPipeline;
        recordingState.markPipelineBound();

        // Reset scissor so it is applied on the next draw.
        scissorDirty = true;
    }

    @Override
    public void setUniform(int index, Object value) {
        requireOpen();
        if (pipeline == null) throw new IllegalStateException("Pipeline must be bound before uniforms");
        // Descriptor-set uniform binding is not yet implemented; throw loudly so callers know.
        throw unsupported("setUniform — descriptor-set binding not yet implemented");
    }

    @Override
    public void pushConstants(ByteBuffer data) {
        requireOpen();
        if (pipeline == null) throw new IllegalStateException("Pipeline must be bound before push constants");
        if (data == null) throw new IllegalArgumentException("pushConstants: data is null");
        int size = pipeline.pushConstantSize();
        if (size <= 0) return; // pipeline has no push-constant range; ignore the call
        if (data.remaining() < size) {
            throw new IllegalArgumentException(
                    "pushConstants: buffer has " + data.remaining() + " bytes but pipeline requires " + size);
        }
        VK10.vkCmdPushConstants(commandBuffer, pipeline.pipelineLayoutHandle(),
                VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT,
                0, data.slice().limit(size));
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        requireOpen();
        pendingScissorX = x;
        pendingScissorY = y;
        pendingScissorW = width;
        pendingScissorH = height;
        scissorDirty = true;
    }

    @Override
    public void disableScissor() {
        requireOpen();
        // Restore to the RenderPearl pass render area, not the whole attachment.
        pendingScissorX = renderAreaX;
        pendingScissorY = renderAreaY;
        pendingScissorW = renderAreaExtentWidth;
        pendingScissorH = renderAreaExtentHeight;
        scissorDirty = true;
    }

    @Override
    public void setVertexBuffer(int slot, GpuBufferSlice slice) {
        requireOpen();
        if (slot < 0 || slot >= MAX_VERTEX_SLOTS) {
            throw new IndexOutOfBoundsException("Vulkan vertex-buffer slot " + slot + " out of range [0, " + MAX_VERTEX_SLOTS + ")");
        }
        if (slice == null) throw new IllegalArgumentException("setVertexBuffer: null slice");
        if (!(slice.buffer() instanceof NativeVulkanGpuBuffer vkBuffer)) {
            throw new IllegalArgumentException("Foreign buffer bound to NativeVulkanRenderPass");
        }
        recordingState.requireCanSetVertexBuffer(slot, vkBuffer, slice.offset(), vkBuffer.size(), MAX_VERTEX_SLOTS);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handles  = stack.longs(vkBuffer.handle());
            LongBuffer offsets  = stack.longs(slice.offset());
            VK10.vkCmdBindVertexBuffers(commandBuffer, slot, handles, offsets);
        }
        recordingState.markVertexBufferBound();
    }

    @Override
    public void setIndexBuffer(GpuBuffer buffer, IndexType indexType) {
        requireOpen();
        if (buffer == null) throw new IllegalArgumentException("setIndexBuffer: null buffer");
        if (!(buffer instanceof NativeVulkanGpuBuffer vkBuffer)) {
            throw new IllegalArgumentException("Foreign index buffer bound to NativeVulkanRenderPass");
        }
        if (indexType == null) throw new IllegalArgumentException("setIndexBuffer: null index type");
        if ((vkBuffer.usage() & GpuBuffer.USAGE_INDEX) == 0) {
            throw new IllegalArgumentException("setIndexBuffer: buffer lacks INDEX usage");
        }
        recordingState.requireOpen();
        if (!recordingState.inPass()) {
            throw new IllegalStateException("setIndexBuffer outside render pass");
        }
        int vkIndexType = indexType == IndexType.SHORT
                ? VK10.VK_INDEX_TYPE_UINT16 : VK10.VK_INDEX_TYPE_UINT32;
        VK10.vkCmdBindIndexBuffer(commandBuffer, vkBuffer.handle(), 0L, vkIndexType);
        indexBuffer = vkBuffer;
        boundIndexType = indexType;
        indexBufferBound = true;
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount,
                            int firstIndex, int baseVertex, int firstInstance) {
        requireOpen();
        recordingState.drawIndexed(indexCount, firstIndex);
        if (instanceCount <= 0 || firstInstance < 0) {
            throw new IllegalArgumentException("drawIndexed instance count must be positive and firstInstance non-negative");
        }
        if (!indexBufferBound || indexBuffer == null || boundIndexType == null) {
            throw new IllegalStateException("drawIndexed: no index buffer bound");
        }
        if (pipeline == null) throw new IllegalStateException("drawIndexed: no pipeline bound");
        int indexBytes = boundIndexType == IndexType.SHORT ? Short.BYTES : Integer.BYTES;
        NativeVulkanCommandState.validateIndexRange(indexBuffer.size(), firstIndex, indexCount, indexBytes);
        flushScissorAndViewport();
        VK10.vkCmdDrawIndexed(commandBuffer, indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
    }

    @Override
    public void multiDrawIndexed(IntBuffer counts, int firstIndex, int baseVertex, int firstInstance) {
        requireOpen();
        throw unsupported("multiDrawIndexed(IntBuffer)");
    }

    @Override
    public void multiDrawIndexed(PointerBuffer counts, IntBuffer firstIndices,
                                 IntBuffer baseVertices, int instanceCount) {
        requireOpen();
        throw unsupported("multiDrawIndexed(PointerBuffer)");
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice arguments, int drawCount) {
        requireOpen();
        throw unsupported("drawIndexedIndirect");
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        requireOpen();
        recordingState.draw(vertexCount, firstVertex);
        if (instanceCount <= 0 || firstInstance < 0) {
            throw new IllegalArgumentException("draw instance count must be positive and firstInstance non-negative");
        }
        if (pipeline == null) throw new IllegalStateException("draw: no pipeline bound");
        flushScissorAndViewport();
        VK10.vkCmdDraw(commandBuffer, vertexCount, instanceCount, firstVertex, firstInstance);
    }

    @Override
    public void multiDraw(IntBuffer counts, int firstVertex, int firstInstance, int instanceCount) {
        requireOpen();
        throw unsupported("multiDraw(IntBuffer, int, int, int)");
    }

    @Override
    public void multiDraw(IntBuffer counts, IntBuffer firstVertices, int instanceCount) {
        requireOpen();
        throw unsupported("multiDraw(IntBuffer, IntBuffer, int)");
    }

    @Override
    public void drawIndirect(GpuBufferSlice arguments, int drawCount) {
        requireOpen();
        throw unsupported("drawIndirect");
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        requireOpen();
        throw unsupported("writeTimestamp — timestamp query pools are not implemented");
    }

    // -----------------------------------------------------------------------------------------
    // Package-internal lifecycle
    // -----------------------------------------------------------------------------------------

    /** Issues {@code vkCmdEndRenderPass} and releases the transient framebuffer. */
    synchronized void end() {
        if (ended) return;
        ended = true;
        try {
            if (!renderPassBegun && colorClearValues.get(0).isPresent()) {
                beginClearOnlyPass();
            }
            if (renderPassBegun) {
                VK10.vkCmdEndRenderPass(commandBuffer);
            }
        } finally {
            if (deferredFramebuffer != VK10.VK_NULL_HANDLE) {
                long framebuffer = deferredFramebuffer;
                deferredFramebuffer = VK10.VK_NULL_HANDLE;
                frame.retire(() -> VK10.vkDestroyFramebuffer(bootstrap.device(), framebuffer, null));
            }
            if (clearOnlyRenderPass != VK10.VK_NULL_HANDLE) {
                long renderPass = clearOnlyRenderPass;
                clearOnlyRenderPass = VK10.VK_NULL_HANDLE;
                frame.retire(() -> VK10.vkDestroyRenderPass(bootstrap.device(), renderPass, null));
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------------------------

    /**
     * A pass that clears its color attachment but never binds a pipeline still has to begin a
     * render pass, otherwise the clear is never recorded and the image stays uninitialized.
     * The transient render pass is compatible with the attachment format and is retired with
     * the frame.
     */
    private void beginClearOnlyPass() {
        if (deferredDepthView != null) {
            throw new UnsupportedOperationException(
                    "Native Vulkan clear-only render pass with a depth attachment requires a bound pipeline");
        }
        GpuFormat format = deferredColorView.texture().getFormat();
        ColorTargetState target = new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL);
        clearOnlyRenderPass = NativeVulkanPipelineCompiler.createRenderPass(
                bootstrap.device(), List.of(target), null);
        beginRenderPassNow(clearOnlyRenderPass);
    }

    /**
     * Creates the transient {@code VkFramebuffer} for the given {@code VkRenderPass} and records
     * {@code vkCmdBeginRenderPass}.
     */
    private void beginRenderPassNow(long renderPassHandle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            transitionAttachment(stack, deferredColorView.texture(), false,
                    VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            if (deferredDepthView != null) {
                transitionAttachment(stack, deferredDepthView.texture(), true,
                        VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                        VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                        VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
                                | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT);
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Collect attachment views: color, and optionally depth.
            int attachmentCount = deferredDepthView != null ? 2 : 1;
            LongBuffer attachments = stack.mallocLong(attachmentCount);
            attachments.put(0, deferredColorView.handle());
            if (deferredDepthView != null) {
                attachments.put(1, deferredDepthView.handle());
            }

            // Create transient VkFramebuffer.
            LongBuffer fbOut = stack.mallocLong(1);
            VkFramebufferCreateInfo fbci = VkFramebufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .renderPass(renderPassHandle)
                    .pAttachments(attachments)
                    .width(renderAreaWidth)
                    .height(renderAreaHeight)
                    .layers(1);
            int result = VK10.vkCreateFramebuffer(bootstrap.device(), fbci, null, fbOut);
            if (result != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateFramebuffer failed with " + result);
            }
            deferredFramebuffer = fbOut.get(0);

            VkClearValue.Buffer clearValues = VkClearValue.calloc(attachmentCount, stack);
            var colorClear = colorClearValues.get(0);
            if (colorClear.isPresent()) {
                var clearColor = colorClear.get();
                clearValues.get(0).color().float32(0, clearColor.x()).float32(1, clearColor.y())
                        .float32(2, clearColor.z()).float32(3, clearColor.w());
            }
            if (attachmentCount > 1) {
                clearValues.get(1).depthStencil().depth((float) depthClearValue.orElse(1.0)).stencil(0);
            }

            VkRenderPassBeginInfo rpbi = VkRenderPassBeginInfo.calloc(stack)
                    .sType$Default()
                    .renderPass(renderPassHandle)
                    .framebuffer(deferredFramebuffer)
                    .pClearValues(clearValues);
            rpbi.renderArea(VkRect2D.calloc(stack).offset(o -> o.set(renderAreaX, renderAreaY))
                    .extent(e -> e.set(renderAreaExtentWidth, renderAreaExtentHeight)));

            VK10.vkCmdBeginRenderPass(commandBuffer, rpbi, VK10.VK_SUBPASS_CONTENTS_INLINE);
            recordInitialClears(stack);
        }
        renderPassBegun = true;
        deferredColorView.texture().setAttachmentLayout(false, VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        if (deferredDepthView != null) {
            deferredDepthView.texture().setAttachmentLayout(true, VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        }
    }

    private void recordInitialClears(MemoryStack stack) {
        int clearCount = (colorClearValues.get(0).isPresent() ? 1 : 0)
                + (deferredDepthView != null && depthClearValue.isPresent() ? 1 : 0);
        if (clearCount == 0) return;

        VkClearAttachment.Buffer clearAttachments = VkClearAttachment.calloc(clearCount, stack);
        int index = 0;
        if (colorClearValues.get(0).isPresent()) {
            var value = colorClearValues.get(0).orElseThrow();
            clearAttachments.get(index++).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(0)
                    .clearValue().color().float32(0, value.x()).float32(1, value.y())
                    .float32(2, value.z()).float32(3, value.w());
        }
        if (deferredDepthView != null && depthClearValue.isPresent()) {
            clearAttachments.get(index).aspectMask(VK10.VK_IMAGE_ASPECT_DEPTH_BIT).colorAttachment(0)
                    .clearValue().depthStencil().depth((float) depthClearValue.getAsDouble()).stencil(0);
        }
        VkClearRect.Buffer rect = VkClearRect.calloc(1, stack)
                .rect(r -> r.offset(o -> o.set(renderAreaX, renderAreaY))
                        .extent(e -> e.set(renderAreaExtentWidth, renderAreaExtentHeight)))
                .baseArrayLayer(0).layerCount(1);
        VK10.vkCmdClearAttachments(commandBuffer, clearAttachments, rect);
        if (colorClearValues.get(0).isPresent()) {
            deferredColorView.texture().markSubresourcesInitialized(
                    deferredColorView.baseMipLevel(), 0, deferredColorView.layerCount());
        }
        if (deferredDepthView != null && depthClearValue.isPresent()) {
            deferredDepthView.texture().markSubresourcesInitialized(
                    deferredDepthView.baseMipLevel(), 0, deferredDepthView.layerCount());
        }
    }
    private void transitionAttachment(MemoryStack stack, NativeVulkanGpuTexture texture,
                                      boolean depth, int newLayout, int destinationAccess,
                                      int destinationStage) {
        int aspect = depth ? VK10.VK_IMAGE_ASPECT_DEPTH_BIT : VK10.VK_IMAGE_ASPECT_COLOR_BIT;
        int oldLayout = texture.attachmentLayout(depth);
        if (oldLayout != newLayout) {
            int sourceAccess = switch (oldLayout) {
                case VK10.VK_IMAGE_LAYOUT_UNDEFINED -> 0;
                case VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK10.VK_ACCESS_TRANSFER_READ_BIT;
                case VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
                case VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
                        | VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                case VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                        | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
                case VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK10.VK_ACCESS_SHADER_READ_BIT;
                default -> VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT;
            };
            int sourceStage = switch (oldLayout) {
                case VK10.VK_IMAGE_LAYOUT_UNDEFINED -> VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                case VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
                case VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                case VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
                        | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
                case VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                default -> VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            };
            var barrier = org.lwjgl.vulkan.VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .oldLayout(oldLayout).newLayout(newLayout).srcAccessMask(sourceAccess)
                    .dstAccessMask(destinationAccess)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(texture.handle())
                    .subresourceRange(r -> r.aspectMask(aspect).baseMipLevel(0)
                            .levelCount(1).baseArrayLayer(0).layerCount(1));
            VK10.vkCmdPipelineBarrier(frame.commandBuffer(), sourceStage, destinationStage,
                    0, null, null, barrier);
        }
        texture.setAttachmentLayout(depth, newLayout);
    }

    /** Flushes the pending scissor rect and sets the dynamic viewport to the render area. */
    private void flushScissorAndViewport() {
        if (!renderPassBegun) {
            throw new IllegalStateException("Draw recorded before render pass was started (no pipeline bound)");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Dynamic viewport covers the attachment; scissor applies the descriptor render area.
            org.lwjgl.vulkan.VkViewport.Buffer vp =
                    org.lwjgl.vulkan.VkViewport.calloc(1, stack);
            vp.get(0).x(0f).y(0f)
                    .width((float) renderAreaWidth)
                    .height((float) renderAreaHeight)
                    .minDepth(0f).maxDepth(1f);
            VK10.vkCmdSetViewport(commandBuffer, 0, vp);

            // Scissor — flush only when dirty or on the first draw.
            if (scissorDirty || !scissorApplied) {
                VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                scissor.get(0).offset(o -> o.set(pendingScissorX, pendingScissorY))
                        .extent(e -> e.set(pendingScissorW, pendingScissorH));
                VK10.vkCmdSetScissor(commandBuffer, 0, scissor);
                appliedScissorX = pendingScissorX;
                appliedScissorY = pendingScissorY;
                appliedScissorW = pendingScissorW;
                appliedScissorH = pendingScissorH;
                scissorApplied = true;
                scissorDirty = false;
            }
        }
    }

    private void requireOpen() {
        if (ended) throw new IllegalStateException("NativeVulkanRenderPass is already ended");
        state.requireOpen();
    }

    private static UnsupportedOperationException unsupported(String op) {
        return new UnsupportedOperationException("NativeVulkanRenderPass: " + op + " is not yet implemented");
    }
}
