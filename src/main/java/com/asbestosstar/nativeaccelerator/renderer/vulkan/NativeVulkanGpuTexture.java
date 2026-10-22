package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkDevice;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Own-device Vulkan image wrapper. It intentionally uses dedicated allocations while the adapter is under validation. */
public final class NativeVulkanGpuTexture extends BaseGpuTexture {
    private final NativeVulkanDeviceState state;
    private final VkDevice device;
    private final NativeVulkanGpuMemoryAllocator allocator;
    private final NativeVulkanGpuMemoryAllocator.Allocation allocation;
    private final long handle;
    private final int vkFormat;
    private final AtomicInteger layout = new AtomicInteger(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
    private final AtomicInteger depthLayout = new AtomicInteger(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
    private final boolean[] initializedSubresources;
    private final int subresourceLayerCount;
    private final AtomicBoolean closed = new AtomicBoolean();

    public NativeVulkanGpuTexture(NativeVulkanDeviceBootstrap bootstrap,
                                  NativeVulkanDeviceState state,
                                  NativeVulkanGpuMemoryAllocator allocator,
                                  String label, int usage, GpuFormat format,
                                  int width, int height, int depthOrLayers, int mipLevels) {
        super(usage, label == null ? "" : label, format,
                checkedDimension(width, "width"), checkedDimension(height, "height"),
                checkedDimension(depthOrLayers, "depthOrLayers"), checkedDimension(mipLevels, "mipLevels"));
        this.subresourceLayerCount = depthOrLayers;
        this.initializedSubresources = new boolean[Math.multiplyExact(depthOrLayers, mipLevels)];
        this.state = Objects.requireNonNull(state, "state");
        this.device = Objects.requireNonNull(bootstrap, "bootstrap").device();
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        boolean cubeCompatible = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
        NativeVulkanResourceValidation.imageLayers(depthOrLayers, cubeCompatible);
        if (cubeCompatible && width != height) {
            throw new IllegalArgumentException("Cube-compatible Vulkan textures must be square");
        }
        this.vkFormat = NativeVulkanFormat.vkFormat(format);
        long created;
        NativeVulkanGpuMemoryAllocator.Allocation memory;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int flags = cubeCompatible ? VK10.VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT : 0;
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .flags(flags)
                    .imageType(VK10.VK_IMAGE_TYPE_2D)
                    .format(vkFormat)
                    .extent(VkExtent3D.calloc(stack).set(width, height, 1))
                    .mipLevels(mipLevels)
                    .arrayLayers(depthOrLayers)
                    .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(vulkanUsage(usage, format))
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            var result = stack.mallocLong(1);
            int status = VK10.vkCreateImage(device, info, null, result);
            if (status != VK10.VK_SUCCESS) throw new IllegalStateException("vkCreateImage failed with " + status);
            created = result.get(0);
            try {
                VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
                VK10.vkGetImageMemoryRequirements(device, created, requirements);
                memory = allocator.allocate(requirements, 0, VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, false);
                status = VK10.vkBindImageMemory(device, created, memory.memory(), 0L);
                if (status != VK10.VK_SUCCESS) {
                    allocator.free(memory);
                    throw new IllegalStateException("vkBindImageMemory failed with " + status);
                }
            } catch (Throwable failure) {
                VK10.vkDestroyImage(device, created, null);
                throw failure;
            }
        }
        this.handle = created;
        this.allocation = memory;
    }

    private static int checkedDimension(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static int vulkanUsage(int usage, GpuFormat format) {
        int flags = 0;
        if ((usage & GpuTexture.USAGE_COPY_DST) != 0) flags |= VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        if ((usage & GpuTexture.USAGE_COPY_SRC) != 0) flags |= VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0) flags |= VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            flags |= format.hasDepthAspect() || format.hasStencilAspect()
                    ? VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
                    : VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        }
        if (flags == 0) throw new IllegalArgumentException("Vulkan texture has no supported usage bits");
        return flags;
    }

    public long handle() {
        requireOpen();
        return handle;
    }

    VkDevice device() {
        return device;
    }

    NativeVulkanDeviceState deviceState() {
        return state;
    }

    void requireOpenForChild() {
        requireOpen();
    }

    public int vkFormat() {
        return vkFormat;
    }

    public NativeVulkanGpuMemoryAllocator.Allocation allocation() {
        requireOpen();
        return allocation;
    }

    int layout() {
        requireOpen();
        return layout.get();
    }

    void setLayout(int value) {
        requireOpen();
        layout.set(value);
        if (getFormat().hasDepthAspect()) depthLayout.set(value);
    }

    int attachmentLayout(boolean depth) {
        requireOpen();
        return (depth ? depthLayout : layout).get();
    }

    void setAttachmentLayout(boolean depth, int value) {
        requireOpen();
        (depth ? depthLayout : layout).set(value);
    }

    boolean subresourcesInitialized(int mipLevel, int baseLayer, int layerCount) {
        requireOpen();
        int index = Math.multiplyExact(mipLevel, subresourceLayerCount) + baseLayer;
        for (int layer = 0; layer < layerCount; layer++) {
            if (!initializedSubresources[index + layer]) return false;
        }
        return true;
    }

    void markSubresourcesInitialized(int mipLevel, int baseLayer, int layerCount) {
        requireOpen();
        int index = Math.multiplyExact(mipLevel, subresourceLayerCount) + baseLayer;
        java.util.Arrays.fill(initializedSubresources, index, index + layerCount, true);
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        synchronized (state) {
            if (!closed.compareAndSet(false, true)) return;
            NativeVulkanDeviceState.Phase phase = state.phase();
            if (phase == NativeVulkanDeviceState.Phase.CLOSING
                    || phase == NativeVulkanDeviceState.Phase.CLOSED || allocator.isClosed()) return;
            VK10.vkDestroyImage(device, handle, null);
            allocator.free(allocation);
        }
    }

    private void requireOpen() {
        state.requireOpen();
        if (closed.get()) throw new IllegalStateException("Vulkan texture is closed");
    }
}
