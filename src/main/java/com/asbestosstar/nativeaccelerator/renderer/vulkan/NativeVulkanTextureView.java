package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTextureView;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkDevice;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Vulkan image view with explicit parent/device lifetime validation. */
public final class NativeVulkanTextureView extends BaseGpuTextureView {
    private final NativeVulkanGpuTexture texture;
    private final VkDevice device;
    private final long handle;
    private final int layerCount;
    private final AtomicBoolean closed = new AtomicBoolean();

    public NativeVulkanTextureView(NativeVulkanGpuTexture texture, int baseMipLevel, int mipLevels) {
        super(Objects.requireNonNull(texture, "texture"), baseMipLevel, mipLevels);
        NativeVulkanResourceValidation.mipRange(baseMipLevel, mipLevels, texture.getMipLevels());
        this.texture = texture;
        this.device = texture.device();
        long created;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int aspect;
            if (texture.getFormat().hasDepthAspect()) {
                aspect = VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
                if (texture.getFormat().hasStencilAspect()) aspect |= VK10.VK_IMAGE_ASPECT_STENCIL_BIT;
            } else if (texture.getFormat().hasStencilAspect()) {
                aspect = VK10.VK_IMAGE_ASPECT_STENCIL_BIT;
            } else {
                aspect = VK10.VK_IMAGE_ASPECT_COLOR_BIT;
            }
            boolean cubeCompatible = (texture.usage() & com.mojang.renderpearl.api.textures.GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
            int viewType;
            if (cubeCompatible) {
                viewType = VK10.VK_IMAGE_VIEW_TYPE_CUBE;
            } else if (texture.getDepthOrLayers() > 1) {
                viewType = VK10.VK_IMAGE_VIEW_TYPE_2D_ARRAY;
            } else {
                viewType = VK10.VK_IMAGE_VIEW_TYPE_2D;
            }
            int layerCount = cubeCompatible ? 6 : texture.getDepthOrLayers();
            this.layerCount = layerCount;
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(texture.handle())
                    .viewType(viewType)
                    .format(texture.vkFormat())
                    .subresourceRange(VkImageSubresourceRange.calloc(stack)
                            .aspectMask(aspect).baseMipLevel(baseMipLevel).levelCount(mipLevels)
                            .baseArrayLayer(0).layerCount(layerCount));
            var result = stack.mallocLong(1);
            int status = VK10.vkCreateImageView(device, info, null, result);
            if (status != VK10.VK_SUCCESS) throw new IllegalStateException("vkCreateImageView failed with " + status);
            created = result.get(0);
        }
        this.handle = created;
    }

    @Override
    public NativeVulkanGpuTexture texture() {
        texture.requireOpenForChild();
        return texture;
    }

    public long handle() {
        texture.requireOpenForChild();
        if (closed.get()) throw new IllegalStateException("Vulkan texture view is closed");
        return handle;
    }

    int layerCount() { return layerCount; }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        synchronized (texture.deviceState()) {
            if (!closed.compareAndSet(false, true)) return;
            NativeVulkanDeviceState.Phase phase = texture.deviceState().phase();
            if (phase == NativeVulkanDeviceState.Phase.CLOSING
                    || phase == NativeVulkanDeviceState.Phase.CLOSED) return;
            VK10.vkDestroyImageView(device, handle, null);
        }
    }
}
