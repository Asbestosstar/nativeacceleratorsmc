package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;

import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;

final class MetalSampler implements GpuSampler {
    private final MetalDevice device;
    private final long handle;
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
            MetalInterop.set(info, "mipmap_mode", MetalInterop.sdl(min == FilterMode.LINEAR
                    ? "SDL_GPU_SAMPLERMIPMAPMODE_LINEAR" : "SDL_GPU_SAMPLERMIPMAPMODE_NEAREST"));
            MetalInterop.set(info, "address_mode_u", MetalConversions.samplerAddress(u));
            MetalInterop.set(info, "address_mode_v", MetalConversions.samplerAddress(v));
            MetalInterop.set(info, "address_mode_w", MetalConversions.samplerAddress(v));
            MetalInterop.set(info, "mip_lod_bias", 0.0f);
            MetalInterop.set(info, "max_anisotropy", (float)Math.max(1, anisotropy));
            MetalInterop.set(info, "compare_op", MetalInterop.sdl("SDL_GPU_COMPAREOP_ALWAYS"));
            MetalInterop.set(info, "min_lod", 0.0f);
            MetalInterop.set(info, "max_lod", (float)maxLod.orElse(1000.0));
            MetalInterop.set(info, "enable_anisotropy", anisotropy > 1);
            MetalInterop.set(info, "enable_compare", false);
            this.handle = MetalInterop.sdlLong("SDL_CreateGPUSampler", device.handle(), info);
        } finally { MetalInterop.free(info); }
        if (handle == 0L) throw new IllegalStateException("SDL_CreateGPUSampler failed: " + MetalInterop.lastSdlError());
    }

    long handle() { if (closed.get()) throw new IllegalStateException("Sampler is closed"); return handle; }
    @Override public AddressMode getAddressModeU() { return u; }
    @Override public AddressMode getAddressModeV() { return v; }
    @Override public FilterMode getMinFilter() { return min; }
    @Override public FilterMode getMagFilter() { return mag; }
    @Override public int getMaxAnisotropy() { return anisotropy; }
    @Override public OptionalDouble getMaxLod() { return maxLod; }
    @Override public boolean isClosed() { return closed.get(); }
    @Override public void close() { if (closed.compareAndSet(false, true)) MetalInterop.sdlCall("SDL_ReleaseGPUSampler", device.handle(), handle); }
}
