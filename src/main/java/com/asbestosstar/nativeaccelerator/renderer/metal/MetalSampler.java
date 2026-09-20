package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;

final class MetalSampler implements GpuSampler {
    private final MetalDevice device;
    private final long handle;
    private final Map<Long, Long> viewHandles = new HashMap<>();
    private final AddressMode u, v;
    private final FilterMode min, mag;
    private final int anisotropy;
    private final OptionalDouble maxLod;
    private final AtomicBoolean closed = new AtomicBoolean();

    MetalSampler(MetalDevice device, AddressMode u, AddressMode v, FilterMode min, FilterMode mag,
                 int anisotropy, OptionalDouble maxLod) {
        this.device = device; this.u = u; this.v = v; this.min = min; this.mag = mag;
        this.anisotropy = anisotropy; this.maxLod = maxLod;
        Object info = MetalInterop.calloc("SDL_GPUSamplerCreateInfo");
        try {
            MetalInterop.set(info, "min_filter", MetalConversions.filter(min));
            MetalInterop.set(info, "mag_filter", MetalConversions.filter(mag));
            // Match RenderPearl's Vulkan sampler semantics exactly: mip filtering is controlled by
            // whether the sampler can address beyond the base level, not by the minification filter.
            // Vulkan also clamps max LOD to at least 0.25 to avoid the zero-LOD edge case used by
            // several GUI/atlas samplers.
            double effectiveMaxLod = maxLod.orElse(1000.0);
            MetalInterop.set(info, "mipmap_mode", MetalInterop.sdl(effectiveMaxLod > 0.25
                    ? "SDL_GPU_SAMPLERMIPMAPMODE_LINEAR" : "SDL_GPU_SAMPLERMIPMAPMODE_NEAREST"));
            MetalInterop.set(info, "address_mode_u", MetalConversions.samplerAddress(u));
            MetalInterop.set(info, "address_mode_v", MetalConversions.samplerAddress(v));
            MetalInterop.set(info, "address_mode_w", MetalConversions.samplerAddress(v));
            MetalInterop.set(info, "mip_lod_bias", 0.0f);
            MetalInterop.set(info, "max_anisotropy", (float)Math.max(1, anisotropy));
            MetalInterop.set(info, "compare_op", MetalInterop.sdl("SDL_GPU_COMPAREOP_ALWAYS"));
            MetalInterop.set(info, "min_lod", 0.0f);
            MetalInterop.set(info, "max_lod", (float)Math.max(0.25, effectiveMaxLod));
            MetalInterop.set(info, "enable_anisotropy", anisotropy > 1);
            MetalInterop.set(info, "enable_compare", false);
            this.handle = MetalInterop.sdlLong("SDL_CreateGPUSampler", device.handle(), info);
        } finally { MetalInterop.free(info); }
        if (handle == 0L) throw new IllegalStateException("SDL_CreateGPUSampler failed: " + MetalInterop.lastSdlError());
    }


    /**
     * SDL GPU does not expose Vulkan-style sampled texture-view objects.  Approximate RenderPearl's
     * mip-view semantics with a view-specific sampler: bias implicit/explicit LOD by baseMipLevel and
     * clamp the accessible LOD interval to the view.  This preserves the important vanilla use case
     * where a nonzero-base-mip view is sampled as if that mip were level zero.
     */
    synchronized long handleForView(MetalTextureView view) {
        if (closed.get()) throw new IllegalStateException("Sampler is closed");
        int baseMip = view.baseMipLevel();
        int levels = view.mipLevels();
        int textureLevels = view.metalTexture().getMipLevels();
        if (baseMip == 0 && levels >= textureLevels) return handle;
        long key = ((long)baseMip << 32) ^ (levels & 0xffffffffL);
        Long existing = viewHandles.get(key);
        if (existing != null) return existing;
        double relativeMax = Math.max(0.25, maxLod.orElse(1000.0));
        double maxAccessible = Math.max(baseMip, Math.min((double)(baseMip + Math.max(1, levels) - 1), baseMip + relativeMax));
        long derived = createHandle((float)baseMip, (float)maxAccessible, (float)baseMip);
        viewHandles.put(key, derived);
        return derived;
    }

    private long createHandle(float minLod, float maxLodValue, float lodBias) {
        Object info = MetalInterop.calloc("SDL_GPUSamplerCreateInfo");
        try {
            MetalInterop.set(info, "min_filter", MetalConversions.filter(min));
            MetalInterop.set(info, "mag_filter", MetalConversions.filter(mag));
            MetalInterop.set(info, "mipmap_mode", MetalInterop.sdl(maxLodValue > minLod + 0.25f
                    ? "SDL_GPU_SAMPLERMIPMAPMODE_LINEAR" : "SDL_GPU_SAMPLERMIPMAPMODE_NEAREST"));
            MetalInterop.set(info, "address_mode_u", MetalConversions.samplerAddress(u));
            MetalInterop.set(info, "address_mode_v", MetalConversions.samplerAddress(v));
            MetalInterop.set(info, "address_mode_w", MetalConversions.samplerAddress(v));
            MetalInterop.set(info, "mip_lod_bias", lodBias);
            MetalInterop.set(info, "max_anisotropy", (float)Math.max(1, anisotropy));
            MetalInterop.set(info, "compare_op", MetalInterop.sdl("SDL_GPU_COMPAREOP_ALWAYS"));
            MetalInterop.set(info, "min_lod", minLod);
            MetalInterop.set(info, "max_lod", maxLodValue);
            MetalInterop.set(info, "enable_anisotropy", anisotropy > 1);
            MetalInterop.set(info, "enable_compare", false);
            long h = MetalInterop.sdlLong("SDL_CreateGPUSampler", device.handle(), info);
            if (h == 0L) throw new IllegalStateException("SDL_CreateGPUSampler(view) failed: " + MetalInterop.lastSdlError());
            return h;
        } finally {
            MetalInterop.free(info);
        }
    }

    long handle() { if (closed.get()) throw new IllegalStateException("Sampler is closed"); return handle; }
    @Override public AddressMode getAddressModeU() { return u; }
    @Override public AddressMode getAddressModeV() { return v; }
    @Override public FilterMode getMinFilter() { return min; }
    @Override public FilterMode getMagFilter() { return mag; }
    @Override public int getMaxAnisotropy() { return anisotropy; }
    @Override public OptionalDouble getMaxLod() { return maxLod; }
    @Override public boolean isClosed() { return closed.get(); }
    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (long derived : viewHandles.values()) {
            if (derived != 0L && derived != handle) MetalInterop.sdlCall("SDL_ReleaseGPUSampler", device.handle(), derived);
        }
        viewHandles.clear();
        MetalInterop.sdlCall("SDL_ReleaseGPUSampler", device.handle(), handle);
    }
}
