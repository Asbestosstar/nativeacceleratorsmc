package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearDepthStencilValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Command encoder for the owned Vulkan device.
 *
 * <p>Transfer and clear operations are implemented using raw LWJGL Vulkan calls. Staging buffers
 * are allocated through the optional {@link NativeVulkanGpuMemoryAllocator}; if it is absent,
 * operations that require staging fail with a clear error rather than silently doing nothing.</p>
 *
 * <p>Staging resources are collected during recording and freed in {@link #close()}. The caller
 * must ensure the GPU has finished executing the submitted command buffer before calling
 * {@code close()} — in the current frame-slot scheme this is guaranteed by the per-frame fence
 * wait in {@link NativeVulkanSwapchainSession#acquireNextTexture()} for the same slot.</p>
 *
 * <p>Operations whose correct completion semantics cannot be proven without additional
 * infrastructure (fence-tracked GPU readback callbacks, transient memory, timestamp queries)
 * remain {@code throw unsupported(…)} until that infrastructure exists.</p>
 */
final class NativeVulkanCommandEncoder implements CommandEncoderBackend {
    private final NativeVulkanDeviceState state;
    private final NativeVulkanFrameSubmission frame;
    private final NativeVulkanCommandState recordingState = new NativeVulkanCommandState();
    /** Nullable: present only when staging uploads are supported. */
    private final NativeVulkanDeviceBootstrap bootstrap;
    /** Nullable: present only when staging uploads are supported. */
    private final NativeVulkanGpuMemoryAllocator allocator;
    /** Staging buffers created during recording. */
    private final List<StagingResource> stagingResources = new ArrayList<>();
    private NativeVulkanRenderPass activePass;
    private boolean closed;

    NativeVulkanCommandEncoder(NativeVulkanDeviceState state, NativeVulkanFrameSubmission frame) {
        this(state, frame, null, null);
    }

    NativeVulkanCommandEncoder(NativeVulkanDeviceState state, NativeVulkanFrameSubmission frame,
                               NativeVulkanDeviceBootstrap bootstrap,
                               NativeVulkanGpuMemoryAllocator allocator) {
        this.state = Objects.requireNonNull(state, "state");
        this.frame = Objects.requireNonNull(frame, "frame");
        this.bootstrap = bootstrap;
        this.allocator = allocator;
    }

    private static final class StagingResource {
        final long bufferHandle;
        final NativeVulkanGpuMemoryAllocator.Allocation allocation;
        StagingResource(long bufferHandle, NativeVulkanGpuMemoryAllocator.Allocation allocation) {
            this.bufferHandle = bufferHandle;
            this.allocation = allocation;
        }
    }

    @Override public synchronized void submit() {
        requireOpen();
        if (activePass != null) throw new IllegalStateException("Cannot submit inside a render pass");
        if (frame.isRecording()) {
            throw new UnsupportedOperationException(
                    "Native Vulkan surface frames are submitted by GpuSurfaceBackend.present()");
        }
        if (!stagingResources.isEmpty()) throw new IllegalStateException("Staging uploads have no recording frame command buffer");
        recordingState.submit();
    }

    @Override public TransientMemory transientMemory() {
        requireOpen();
        throw unsupported("transientMemory");
    }

    @Override public synchronized RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        requireOpen();
        if (activePass != null) throw new IllegalStateException("Nested Native Vulkan render pass");
        Objects.requireNonNull(descriptor, "descriptor");
        if (bootstrap == null) throw new IllegalStateException("createRenderPass requires a live device bootstrap");
        String passName = descriptor.colorAttachments().isEmpty() ? "render-pass"
                : "render-pass[" + descriptor.colorAttachments().size() + "ca]";
        Object firstTarget = descriptor.colorAttachments().isEmpty()
                ? null : descriptor.colorAttachments().get(0).textureView();
        recordingState.requireCanBeginPass(passName, firstTarget);
        NativeVulkanRenderPass pass = new NativeVulkanRenderPass(
                bootstrap, state, recordingState, frame, frame.commandBuffer(), descriptor);
        recordingState.enterPass();
        activePass = pass;
        return pass;
    }

    @Override public synchronized void submitRenderPass() {
        requireOpen();
        if (activePass == null) throw new IllegalStateException("No Native Vulkan render pass active");
        NativeVulkanRenderPass pass = activePass;
        activePass = null;
        pass.end();
        recordingState.leavePass();
    }

    @Override public void clearColorTexture(GpuTexture texture, Vector4fc value) {
        requireOpen(); requireNoPass("clearColorTexture");
        NativeVulkanGpuTexture image = vulkanTexture(texture);
        ensureUsage(image, GpuTexture.USAGE_COPY_DST, "clearColorTexture");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int aspect = aspectMaskForClear(image);
            int oldLayout = image.layout();
            int srcStage = pipelineStageForLayout(oldLayout);
            VkImageMemoryBarrier.Buffer barrier = transitionBarrier(stack, image, oldLayout,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, accessMaskForLayout(oldLayout), srcStage,
                    VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    aspect, 0, image.getMipLevels(), 0, image.getDepthOrLayers());
            VK10.vkCmdPipelineBarrier(frame.commandBuffer(), srcStage, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, null, null, barrier);
            VkClearColorValue clear = VkClearColorValue.calloc(stack).float32(0, clamp01(value.x()))
                    .float32(1, clamp01(value.y())).float32(2, clamp01(value.z())).float32(3, clamp01(value.w()));
            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack).aspectMask(aspect)
                    .baseMipLevel(0).levelCount(image.getMipLevels()).baseArrayLayer(0).layerCount(image.getDepthOrLayers());
            VK10.vkCmdClearColorImage(frame.commandBuffer(), image.handle(),
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, range);
            image.setLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            image.markSubresourcesInitialized(0, 0, image.getDepthOrLayers());
        }
    }

    @Override public void clearColorAndDepthTextures(GpuTexture color, Vector4fc value, GpuTexture depth, double depthValue) {
        clearColorAndDepthTextures(color, value, depth, depthValue, 0, 0,
                vulkanTexture(color).getWidth(0), vulkanTexture(color).getHeight(0), 0);
    }

    @Override public void clearColorAndDepthTextures(GpuTexture color, Vector4fc value, GpuTexture depth,
                                                      double depthValue, int regionX, int regionY,
                                                      int regionWidth, int regionHeight, int mipLevel) {
        requireOpen(); requireNoPass("clearColorAndDepthTextures");
        NativeVulkanGpuTexture colorImage = vulkanTexture(color);
        NativeVulkanGpuTexture depthImage = vulkanTexture(depth);
        ensureUsage(colorImage, GpuTexture.USAGE_COPY_DST, "clearColorAndDepthTextures");
        ensureUsage(depthImage, GpuTexture.USAGE_COPY_DST, "clearColorAndDepthTextures");
        ensureDepthFormat(depthImage, "clearColorAndDepthTextures");
        validateClearRegion(colorImage, regionX, regionY, regionWidth, regionHeight, mipLevel);
        validateClearRegion(depthImage, regionX, regionY, regionWidth, regionHeight, mipLevel);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            transitionToTransferDst(stack, colorImage, VK10.VK_IMAGE_ASPECT_COLOR_BIT, mipLevel, 1, 0, 1);
            int depthAspect = depthAspectMask(depthImage);
            transitionToTransferDst(stack, depthImage, depthAspect, mipLevel, 1, 0, 1);
            VkClearColorValue colorClear = VkClearColorValue.calloc(stack).float32(0, clamp01(value.x()))
                    .float32(1, clamp01(value.y())).float32(2, clamp01(value.z())).float32(3, clamp01(value.w()));
            VkImageSubresourceRange colorRange = clearRange(stack, VK10.VK_IMAGE_ASPECT_COLOR_BIT, mipLevel);
            VK10.vkCmdClearColorImage(frame.commandBuffer(), colorImage.handle(),
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, colorClear, colorRange);
            VkClearDepthStencilValue depthClear = VkClearDepthStencilValue.calloc(stack)
                    .depth(clamp01(depthValue)).stencil(0);
            VkImageSubresourceRange depthRange = clearRange(stack, depthAspect, mipLevel);
            VK10.vkCmdClearDepthStencilImage(frame.commandBuffer(), depthImage.handle(),
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, depthClear, depthRange);
            colorImage.setLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            depthImage.setLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            colorImage.markSubresourcesInitialized(mipLevel, 0, 1);
            depthImage.markSubresourcesInitialized(mipLevel, 0, 1);
        }
    }

    private static void validateClearRegion(NativeVulkanGpuTexture image, int x, int y,
                                            int width, int height, int mipLevel) {
        if (mipLevel < 0 || mipLevel >= image.getMipLevels() || x != 0 || y != 0
                || width != image.getWidth(mipLevel) || height != image.getHeight(mipLevel)) {
            throw new UnsupportedOperationException("Vulkan image clear supports full-mip regions only");
        }
    }

    private static VkImageSubresourceRange clearRange(MemoryStack stack, int aspect, int mipLevel) {
        return VkImageSubresourceRange.calloc(stack).aspectMask(aspect)
                .baseMipLevel(mipLevel).levelCount(1).baseArrayLayer(0).layerCount(1);
    }

    @Override public void clearDepthTexture(GpuTexture texture, double value) {
        requireOpen(); requireNoPass("clearDepthTexture");
        NativeVulkanGpuTexture image = vulkanTexture(texture);
        ensureUsage(image, GpuTexture.USAGE_COPY_DST, "clearDepthTexture");
        ensureDepthFormat(image, "clearDepthTexture");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int aspect = depthAspectMask(image);
            transitionToTransferDst(stack, image, aspect);
            VkClearDepthStencilValue clear = VkClearDepthStencilValue.calloc(stack).depth(clamp01(value)).stencil(0);
            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack).aspectMask(aspect)
                    .baseMipLevel(0).levelCount(image.getMipLevels()).baseArrayLayer(0).layerCount(image.getDepthOrLayers());
            VK10.vkCmdClearDepthStencilImage(frame.commandBuffer(), image.handle(),
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, range);
            for (int mip = 0; mip < image.getMipLevels(); mip++) {
                image.markSubresourcesInitialized(mip, 0, image.getDepthOrLayers());
            }
        }
    }

    @Override public void writeToBuffer(GpuBufferSlice dst, ByteBuffer data) {
        requireOpen(); requireNoPass("writeToBuffer");
        Objects.requireNonNull(data, "data");
        NativeVulkanGpuBuffer buffer = vulkanBuffer(dst);
        long bytes = data.remaining();
        validateBufferRange(buffer, dst.offset(), bytes, "writeToBuffer");
        NativeVulkanGpuMemoryAllocator.Allocation allocation = buffer.allocation();
        if (allocation.hostVisible()) {
            ByteBuffer mapped = allocation.map();
            ByteBuffer source = data.duplicate();
            mapped.position(Math.toIntExact(dst.offset()));
            mapped.put(source);
        if (!allocation.hostCoherent()) allocator.flush(allocation, dst.offset(), bytes);
            return;
        }
        NativeVulkanGpuBuffer staging = createStagingBuffer(data, bytes);
        copyBuffer(staging.handle(), buffer.handle(), 0L, dst.offset(), bytes);
    }

    @Override public void copyToBuffer(GpuBufferSlice src, GpuBufferSlice dst) {
        requireOpen(); requireNoPass("copyToBuffer");
        NativeVulkanGpuBuffer source = vulkanBuffer(src);
        NativeVulkanGpuBuffer destination = vulkanBuffer(dst);
        long bytes = Math.min(src.length(), dst.length());
        validateBufferRange(source, src.offset(), bytes, "copyToBuffer source");
        validateBufferRange(destination, dst.offset(), bytes, "copyToBuffer destination");
        copyBuffer(source.handle(), destination.handle(), src.offset(), dst.offset(), bytes);
    }

    private void copyBuffer(long source, long destination, long sourceOffset, long destinationOffset, long bytes) {
        if (bytes <= 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                    .srcOffset(sourceOffset).dstOffset(destinationOffset).size(bytes);
            VK10.vkCmdCopyBuffer(frame.commandBuffer(), source, destination, region);
        }
    }

    @Override public void writeToTexture(GpuTexture destination, ByteBuffer data,
                                         int mipLevel, int x, int y, int z, int width, int height) {
        requireOpen(); requireNoPass("writeToTexture");
        Objects.requireNonNull(data, "data");
        NativeVulkanGpuTexture image = vulkanTexture(destination);
        ensureUsage(image, GpuTexture.USAGE_COPY_DST, "writeToTexture");
        if (mipLevel < 0 || mipLevel >= image.getMipLevels() || width <= 0 || height <= 0
                || x < 0 || y < 0 || z < 0) throw new IllegalArgumentException("Invalid texture upload region");
        int mipWidth = Math.max(1, image.getWidth(mipLevel));
        int mipHeight = Math.max(1, image.getHeight(mipLevel));
        if ((long) x + width > mipWidth || (long) y + height > mipHeight || z >= image.getDepthOrLayers())
            throw new IllegalArgumentException("Texture upload region exceeds image bounds");
        long bytes = Math.multiplyExact(Math.multiplyExact((long) width, height), bytesPerPixel(image.getFormat()));
        if (data.remaining() < bytes) throw new IllegalArgumentException("Texture upload data is too small");
        StagingResource staging = createStagingBufferResource(data, bytes);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            transitionToTransferDst(stack, image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, mipLevel, 1, z, 1);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0)
                    .imageSubresource(s -> s.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(mipLevel)
                            .baseArrayLayer(z).layerCount(1))
                    .imageOffset(o -> o.set(x, y, 0)).imageExtent(e -> e.set(width, height, 1));
            VK10.vkCmdCopyBufferToImage(frame.commandBuffer(), staging.bufferHandle, image.handle(),
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            image.setLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            image.markSubresourcesInitialized(mipLevel, z, 1);
        }
    }

    @Override public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY,
                                               int sourceWidth, int sourceHeight, GpuTexture destination,
                                               int destX, int destY, int copyWidth, int copyHeight,
                                               int mip, int layer) {
        requireOpen(); requireNoPass("copyBufferToTexture");
        NativeVulkanGpuBuffer buffer = vulkanBuffer(source);
        NativeVulkanGpuTexture image = vulkanTexture(destination);
        ensureUsage(buffer, GpuBuffer.USAGE_COPY_SRC, "copyBufferToTexture source");
        ensureUsage(image, GpuTexture.USAGE_COPY_DST, "copyBufferToTexture destination");
        if (mip < 0 || mip >= image.getMipLevels() || layer < 0 || layer >= image.getDepthOrLayers()
                || sourceX < 0 || sourceY < 0 || sourceWidth <= 0 || sourceHeight <= 0
                || destX < 0 || destY < 0 || copyWidth <= 0 || copyHeight <= 0
                || (long) destX + copyWidth > image.getWidth(mip) || (long) destY + copyHeight > image.getHeight(mip)) {
            throw new IllegalArgumentException("Invalid buffer-to-image copy region");
        }
        int pixelBytes = bytesPerPixel(image.getFormat());
        long rowBytes = Math.multiplyExact((long) sourceWidth, pixelBytes);
        long byteOffset = Math.addExact(source.offset(), Math.multiplyExact((long) sourceY, rowBytes)
                + Math.multiplyExact((long) sourceX, pixelBytes));
        long bytes = Math.multiplyExact(Math.multiplyExact((long) copyWidth, copyHeight), pixelBytes);
        validateBufferRange(buffer, byteOffset, bytes, "copyBufferToTexture source");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            transitionToTransferDst(stack, image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, mip, 1, layer, 1);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(byteOffset).bufferRowLength(sourceWidth).bufferImageHeight(sourceHeight)
                    .imageSubresource(s -> s.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(mip)
                            .baseArrayLayer(layer).layerCount(1))
                    .imageOffset(o -> o.set(destX, destY, 0)).imageExtent(e -> e.set(copyWidth, copyHeight, 1));
            VK10.vkCmdCopyBufferToImage(frame.commandBuffer(), buffer.handle(), image.handle(),
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            image.setLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            image.markSubresourcesInitialized(mip, layer, 1);
        }
    }

    public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mip,
                                     int destX, int destY, int sourceX, int sourceY, int width, int height) {
        requireOpen(); requireNoPass("copyTextureToTexture");
        copyTextureRegion(vulkanTexture(source), vulkanTexture(destination), mip,
                sourceX, sourceY, destX, destY, 0, width, height);
    }

    private void copyTextureRegion(NativeVulkanGpuTexture src, NativeVulkanGpuTexture dst, int mip,
                                   int sourceX, int sourceY, int destX, int destY, int layer,
                                   int width, int height) {
        ensureUsage(src, GpuTexture.USAGE_COPY_SRC, "copyTextureToTexture source");
        ensureUsage(dst, GpuTexture.USAGE_COPY_DST, "copyTextureToTexture destination");
        if (src.getFormat() != dst.getFormat() || mip < 0 || mip >= src.getMipLevels()
                || mip >= dst.getMipLevels() || layer < 0 || layer >= src.getDepthOrLayers()
                || layer >= dst.getDepthOrLayers() || width <= 0 || height <= 0
                || sourceX < 0 || sourceY < 0 || destX < 0 || destY < 0
                || (long) sourceX + width > src.getWidth(mip) || (long) sourceY + height > src.getHeight(mip)
                || (long) destX + width > dst.getWidth(mip) || (long) destY + height > dst.getHeight(mip)) {
            throw new IllegalArgumentException("Invalid Vulkan texture copy region");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            transitionToTransferSrc(stack, src, VK10.VK_IMAGE_ASPECT_COLOR_BIT, mip, 1);
            transitionToTransferDst(stack, dst, VK10.VK_IMAGE_ASPECT_COLOR_BIT, mip, 1, layer, 1);
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack)
                    .srcSubresource(s -> s.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(mip).baseArrayLayer(layer).layerCount(1))
                    .srcOffset(o -> o.set(sourceX, sourceY, 0))
                    .dstSubresource(s -> s.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(mip).baseArrayLayer(layer).layerCount(1))
                    .dstOffset(o -> o.set(destX, destY, 0)).extent(e -> e.set(width, height, 1));
            VK10.vkCmdCopyImage(frame.commandBuffer(), src.handle(), VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dst.handle(), VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            src.setLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            dst.setLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            if (src.subresourcesInitialized(mip, layer, 1)) dst.markSubresourcesInitialized(mip, layer, 1);
        }
    }

    @Override public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long destinationOffset,
                                               Runnable callback, int bytesPerPixel) {
        requireOpen();
        throw unsupported("copyTextureToBuffer requires fence-backed callback completion");
    }

    @Override public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long destinationOffset,
                                               Runnable callback, int bytesPerPixel,
                                               int x, int y, int width, int height) {
        requireOpen();
        throw unsupported("copyTextureToBuffer(region) requires fence-backed callback completion");
    }

    @Override public GpuFence createFence() { requireOpen(); throw unsupported("createFence"); }
    @Override public void writeTimestamp(GpuQueryPool pool, int index) { requireOpen(); throw unsupported("writeTimestamp"); }
    private void copyTextureToTexture(GpuTexture source, GpuTexture destination) {
        requireOpen(); requireNoPass("copyToTexture");
        NativeVulkanGpuTexture src = vulkanTexture(source);
        NativeVulkanGpuTexture dst = vulkanTexture(destination);
        if (src.getFormat() != dst.getFormat() || src.getWidth(0) != dst.getWidth(0)
                || src.getHeight(0) != dst.getHeight(0) || src.getDepthOrLayers() != dst.getDepthOrLayers()) {
            throw new IllegalArgumentException("copyToTexture requires matching image dimensions and formats");
        }
        copyTextureRegion(src, dst, 0, 0, 0, 0, 0, 0, src.getWidth(0), src.getHeight(0));
    }

    void close() {
        if (closed) return;
        if (activePass != null) {
            activePass.end();
            activePass = null;
        }
        if (recordingState.phase() == NativeVulkanCommandState.Phase.IN_PASS) {
            recordingState.leavePass();
        }
        closed = true;
        recordingState.close();
        releaseStagingResources();
    }

    private StagingResource createStagingBufferResource(ByteBuffer data, long bytes) {
        if (allocator == null || bootstrap == null) throw unsupported("staging upload without a GPU memory allocator");
        NativeVulkanGpuMemoryAllocator.Allocation allocation = null;
        long buffer = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = org.lwjgl.vulkan.VkBufferCreateInfo.calloc(stack).sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(bytes).usage(VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT).sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            var out = stack.mallocLong(1);
            checkVk(VK10.vkCreateBuffer(bootstrap.device(), info, null, out), "vkCreateBuffer (staging)");
            buffer = out.get(0);
            var requirements = org.lwjgl.vulkan.VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(bootstrap.device(), buffer, requirements);
            allocation = allocator.allocate(requirements, VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                    VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
            checkVk(VK10.vkBindBufferMemory(bootstrap.device(), buffer, allocation.memory(), 0L), "vkBindBufferMemory (staging)");
            java.nio.ByteBuffer target = allocation.map();
            target.put(0, data, data.position(), (int) bytes);
            if (!allocation.hostCoherent()) allocator.flush(allocation, 0L, bytes);
            StagingResource resource = new StagingResource(buffer, allocation);
            stagingResources.add(resource);
            buffer = 0L;
            allocation = null;
            return resource;
        } catch (Throwable failure) {
            if (allocation != null) allocator.free(allocation);
            if (buffer != 0L) VK10.vkDestroyBuffer(bootstrap.device(), buffer, null);
            throw failure;
        }
    }

    private NativeVulkanGpuBuffer createStagingBuffer(ByteBuffer data, long bytes) {
        return new NativeVulkanGpuBuffer(bootstrap, state, allocator,
                GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_MAP_WRITE, data.duplicate());
    }

    private void releaseStagingResources() {
        if (stagingResources.isEmpty() || bootstrap == null || allocator == null) return;
        for (StagingResource resource : stagingResources) {
            VK10.vkDestroyBuffer(bootstrap.device(), resource.bufferHandle, null);
            allocator.free(resource.allocation);
        }
        stagingResources.clear();
    }

    synchronized boolean isOpen() {
        return !closed;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Native Vulkan command encoder is closed");
        state.requireOpen();
    }

    private void requireNoPass(String operation) {
        if (activePass != null) throw new IllegalStateException(operation + " cannot execute inside a render pass");
    }

    private NativeVulkanGpuBuffer vulkanBuffer(GpuBufferSlice slice) {
        Objects.requireNonNull(slice, "slice");
        if (!(slice.buffer() instanceof NativeVulkanGpuBuffer buffer)) throw new IllegalArgumentException("Foreign Vulkan buffer");
        return buffer;
    }

    private NativeVulkanGpuBuffer vulkanBuffer(GpuBuffer buffer) {
        if (!(buffer instanceof NativeVulkanGpuBuffer nativeBuffer)) throw new IllegalArgumentException("Foreign Vulkan buffer");
        return nativeBuffer;
    }

    private NativeVulkanGpuTexture vulkanTexture(GpuTexture texture) {
        if (!(texture instanceof NativeVulkanGpuTexture nativeTexture)) throw new IllegalArgumentException("Foreign Vulkan texture");
        return nativeTexture;
    }

    private void ensureUsage(NativeVulkanGpuTexture texture, int usage, String operation) {
        if ((texture.usage() & usage) == 0) throw new IllegalArgumentException(operation + ": texture lacks required usage");
    }

    private void ensureUsage(NativeVulkanGpuBuffer buffer, int usage, String operation) {
        if ((buffer.usage() & usage) == 0) throw new IllegalArgumentException(operation + ": buffer lacks required usage");
    }

    private void validateBufferRange(NativeVulkanGpuBuffer buffer, long offset, long size, String operation) {
        if (offset < 0 || size < 0 || offset > buffer.size() - size) throw new IllegalArgumentException(operation + ": buffer range out of bounds");
    }

    private void transitionToTransferDst(MemoryStack stack, NativeVulkanGpuTexture image, int aspect) {
        transitionToTransferDst(stack, image, aspect, 0, image.getMipLevels(), 0, image.getDepthOrLayers());
    }

    private void transitionToTransferDst(MemoryStack stack, NativeVulkanGpuTexture image,
                                         int aspect, int baseMip, int mipCount, int baseLayer, int layerCount) {
        int oldLayout = image.layout();
        if (oldLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) return;
        int srcStage = pipelineStageForLayout(oldLayout);
        VkImageMemoryBarrier.Buffer barrier = transitionBarrier(stack, image, oldLayout,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, accessMaskForLayout(oldLayout), srcStage,
                VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                aspect, baseMip, mipCount, baseLayer, layerCount);
        VK10.vkCmdPipelineBarrier(frame.commandBuffer(), srcStage, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, null, null, barrier);
        image.setLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    }

    private void transitionToTransferSrc(MemoryStack stack, NativeVulkanGpuTexture image, int aspect,
                                         int baseMip, int mipCount) {
        int oldLayout = image.layout();
        if (oldLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) return;
        int srcStage = pipelineStageForLayout(oldLayout);
        VkImageMemoryBarrier.Buffer barrier = transitionBarrier(stack, image, oldLayout,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, accessMaskForLayout(oldLayout), srcStage,
                VK10.VK_ACCESS_TRANSFER_READ_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                aspect, baseMip, mipCount, 0, 1);
        VK10.vkCmdPipelineBarrier(frame.commandBuffer(), srcStage, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, null, null, barrier);
        image.setLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    }

    private VkImageMemoryBarrier.Buffer transitionBarrier(MemoryStack stack, NativeVulkanGpuTexture image,
            int oldLayout, int newLayout, int srcAccess, int srcStage, int dstAccess, int dstStage,
            int aspect, int baseMip, int mipCount, int baseLayer, int layerCount) {
        return VkImageMemoryBarrier.calloc(1, stack).sType$Default().oldLayout(oldLayout).newLayout(newLayout)
                .srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(image.handle()).subresourceRange(r -> r.aspectMask(aspect).baseMipLevel(baseMip)
                        .levelCount(mipCount).baseArrayLayer(baseLayer).layerCount(layerCount));
    }

    private static int pipelineStageForLayout(int layout) {
        return switch (layout) {
            case VK10.VK_IMAGE_LAYOUT_UNDEFINED -> VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            case VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
            case VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            case VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            default -> VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        };
    }

    private static int accessMaskForLayout(int layout) {
        return switch (layout) {
            case VK10.VK_IMAGE_LAYOUT_UNDEFINED -> 0;
            case VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK10.VK_ACCESS_TRANSFER_READ_BIT;
            case VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
            case VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            case VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK10.VK_ACCESS_SHADER_READ_BIT;
            default -> VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT;
        };
    }

    void blitToSwapchain(VkCommandBuffer commandBuffer, NativeVulkanSwapchainSession swapchain,
                         NativeVulkanTextureView sourceView) {
        requireOpen();
        if (commandBuffer.address() != frame.commandBuffer().address()) {
            throw new IllegalArgumentException("Swapchain blit command buffer does not belong to this encoder");
        }
        NativeVulkanGpuTexture source = sourceView.texture();
        ensureUsage(source, GpuTexture.USAGE_COPY_SRC, "blitToSwapchain source");
        if (!source.subresourcesInitialized(sourceView.baseMipLevel(), 0, sourceView.layerCount())) {
            throw new IllegalStateException("blitToSwapchain source image contents are uninitialized");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int colorLayers = sourceView.layerCount();
            if (source.getWidth(sourceView.baseMipLevel()) != swapchain.width()
                    || source.getHeight(sourceView.baseMipLevel()) != swapchain.height()) {
                throw new UnsupportedOperationException("Swapchain blit currently requires matching source and surface dimensions");
            }
            transitionToTransferSrc(stack, source, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    sourceView.baseMipLevel(), 1);
            int swapOldLayout = swapchain.currentImageLayout();
            if (swapOldLayout != VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
                VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                        .oldLayout(swapOldLayout).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .srcAccessMask(accessMaskForLayout(swapOldLayout)).dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(swapchain.currentImageHandle())
                        .subresourceRange(r -> r.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0)
                                .levelCount(1).baseArrayLayer(0).layerCount(1));
                VK10.vkCmdPipelineBarrier(commandBuffer, pipelineStageForLayout(swapOldLayout),
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);
            }
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack)
                    .srcSubresource(s -> s.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(sourceView.baseMipLevel()).baseArrayLayer(0).layerCount(colorLayers))
                    .srcOffsets(0, o -> o.set(0, 0, 0))
                    .srcOffsets(1, o -> o.set(source.getWidth(sourceView.baseMipLevel()),
                            source.getHeight(sourceView.baseMipLevel()), 1))
                    .dstSubresource(s -> s.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1))
                    .dstOffsets(0, o -> o.set(0, 0, 0))
                    .dstOffsets(1, o -> o.set(swapchain.width(), swapchain.height(), 1));
            VK10.vkCmdBlitImage(commandBuffer, source.handle(), VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    swapchain.currentImageHandle(), VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);
            VkImageMemoryBarrier.Buffer presentBarrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(0)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(swapchain.currentImageHandle())
                    .subresourceRange(r -> r.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0)
                            .levelCount(1).baseArrayLayer(0).layerCount(1));
            VK10.vkCmdPipelineBarrier(commandBuffer, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, presentBarrier);
            source.setLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            swapchain.setCurrentImageLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        }
    }

    private static int bytesPerPixel(GpuFormat format) {
        return switch (format) {
            case R8_UNORM, R8_SNORM, R8_UINT, R8_SINT -> 1;
            case RG8_UNORM, RG8_SNORM, RG8_UINT, RG8_SINT, R16_UNORM, R16_SNORM,
                    R16_UINT, R16_SINT, R16_FLOAT -> 2;
            case RGB8_UNORM, RGB8_SNORM, RGB8_UINT, RGB8_SINT -> 3;
            case RGBA8_UNORM, RGBA8_SNORM, RGBA8_UINT, RGBA8_SINT,
                    RG16_UNORM, RG16_SNORM, RG16_UINT, RG16_SINT, RG16_FLOAT,
                    R32_UINT, R32_SINT, R32_FLOAT -> 4;
            case RGB16_UNORM, RGB16_SNORM, RGB16_UINT, RGB16_SINT, RGB16_FLOAT -> 6;
            case RGBA16_UNORM, RGBA16_SNORM, RGBA16_UINT, RGBA16_SINT, RGBA16_FLOAT,
                    RG32_UINT, RG32_SINT, RG32_FLOAT -> 8;
            case RGB32_UINT, RGB32_SINT, RGB32_FLOAT -> 12;
            case RGBA32_UINT, RGBA32_SINT, RGBA32_FLOAT -> 16;
            case RGB10A2_UNORM, RGB10A2_UINT, RG11B10_FLOAT, D32_FLOAT, D16_UNORM, S8_UINT -> 4;
            case D32_FLOAT_S8_UINT -> 8;
            case D24_UNORM_S8_UINT -> 4;
        };
    }

    private static int aspectMaskForClear(NativeVulkanGpuTexture image) {
        return image.getFormat() == GpuFormat.D32_FLOAT ? VK10.VK_IMAGE_ASPECT_DEPTH_BIT : VK10.VK_IMAGE_ASPECT_COLOR_BIT;
    }

    private static int depthAspectMask(NativeVulkanGpuTexture image) { return VK10.VK_IMAGE_ASPECT_DEPTH_BIT; }
    private static void ensureDepthFormat(NativeVulkanGpuTexture image, String op) {
        if (image.getFormat() != GpuFormat.D32_FLOAT) throw new IllegalArgumentException(op + ": only D32_FLOAT depth is supported");
    }
    private static float clamp01(double value) { return (float) Math.max(0.0, Math.min(1.0, value)); }
    private static float clamp01(float value) { return Math.max(0.0f, Math.min(1.0f, value)); }
    private static void checkVk(int result, String operation) {
        if (result != VK10.VK_SUCCESS) throw new IllegalStateException(operation + " failed with Vulkan result " + result);
    }
    private static UnsupportedOperationException unsupported(String operation) {
        return new UnsupportedOperationException("Native Vulkan " + operation + " is not implemented");
    }
}
