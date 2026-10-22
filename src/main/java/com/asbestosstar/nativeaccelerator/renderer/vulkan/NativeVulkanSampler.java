package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;

/** Own-device Vulkan sampler wrapper. */
public final class NativeVulkanSampler implements GpuSampler {
    private final NativeVulkanDeviceState state;
    private final VkDevice device;
    private final long handle;
    private final AddressMode addressU;
    private final AddressMode addressV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int anisotropy;
    private final OptionalDouble maxLod;
    private final AtomicBoolean closed = new AtomicBoolean();

    public NativeVulkanSampler(NativeVulkanDeviceBootstrap bootstrap, NativeVulkanDeviceState state,
                               AddressMode addressU, AddressMode addressV, FilterMode minFilter,
                               FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        this.state = Objects.requireNonNull(state, "state");
        this.device = Objects.requireNonNull(bootstrap, "bootstrap").device();
        this.addressU = Objects.requireNonNull(addressU, "addressU");
        this.addressV = Objects.requireNonNull(addressV, "addressV");
        this.minFilter = Objects.requireNonNull(minFilter, "minFilter");
        this.magFilter = Objects.requireNonNull(magFilter, "magFilter");
        this.maxLod = Objects.requireNonNull(maxLod, "maxLod");
        NativeVulkanSamplerPolicy.effectiveMaxLod(maxLod);
        // The bootstrap does not negotiate sampler anisotropy yet. Keep the public metadata truthful:
        // only the capability actually used by this VkSampler is exposed.
        this.anisotropy = NativeVulkanSamplerPolicy.validateAnisotropy(maxAnisotropy, 1);
        int mipmapMode = NativeVulkanSamplerPolicy.usesLinearMipmap(maxLod)
                ? VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR : VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST;
        long created;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(filter(magFilter)).minFilter(filter(minFilter))
                    .mipmapMode(mipmapMode)
                    .addressModeU(address(addressU)).addressModeV(address(addressV)).addressModeW(address(addressV))
                    .mipLodBias(0.0f).anisotropyEnable(false).maxAnisotropy(1.0f)
                    .compareEnable(false).compareOp(VK10.VK_COMPARE_OP_ALWAYS)
                    .minLod(0.0f).maxLod((float) NativeVulkanSamplerPolicy.vkMaxLod(maxLod));
            var result = stack.mallocLong(1);
            int status = VK10.vkCreateSampler(device, info, null, result);
            if (status != VK10.VK_SUCCESS) throw new IllegalStateException("vkCreateSampler failed with " + status);
            created = result.get(0);
        }
        this.handle = created;
    }

    private static int filter(FilterMode mode) {
        return mode == FilterMode.LINEAR ? VK10.VK_FILTER_LINEAR : VK10.VK_FILTER_NEAREST;
    }

    private static int address(AddressMode mode) {
        return mode == AddressMode.REPEAT ? VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT : VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    }

    public long handle() {
        state.requireOpen();
        if (closed.get()) throw new IllegalStateException("Vulkan sampler is closed");
        return handle;
    }

    @Override public AddressMode getAddressModeU() { return addressU; }
    @Override public AddressMode getAddressModeV() { return addressV; }
    @Override public FilterMode getMinFilter() { return minFilter; }
    @Override public FilterMode getMagFilter() { return magFilter; }
    @Override public int getMaxAnisotropy() { return anisotropy; }
    @Override public OptionalDouble getMaxLod() { return maxLod; }
    @Override public boolean isClosed() { return closed.get(); }

    @Override
    public void close() {
        synchronized (state) {
            if (!closed.compareAndSet(false, true)) return;
            NativeVulkanDeviceState.Phase phase = state.phase();
            if (phase == NativeVulkanDeviceState.Phase.CLOSING
                    || phase == NativeVulkanDeviceState.Phase.CLOSED) return;
            VK10.vkDestroySampler(device, handle, null);
        }
    }
}
