package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;

import java.util.concurrent.atomic.AtomicBoolean;

final class MetalGpuTexture extends BaseGpuTexture {
    private final MetalDevice device;
    private final long handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    MetalGpuTexture(MetalDevice device, String label, int usage, GpuFormat format,
                    int width, int height, int depthOrLayers, int mipLevels) {
        super(usage, label == null ? "" : label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        Object info = MetalInterop.calloc("SDL_GPUTextureCreateInfo");
        try {
            int type;
            if ((usage & 16) != 0) {
                type = MetalInterop.sdl(depthOrLayers == 6 ? "SDL_GPU_TEXTURETYPE_CUBE" : "SDL_GPU_TEXTURETYPE_CUBE_ARRAY");
            } else {
                type = MetalInterop.sdl(depthOrLayers > 1 ? "SDL_GPU_TEXTURETYPE_2D_ARRAY" : "SDL_GPU_TEXTURETYPE_2D");
            }
            MetalInterop.set(info, "type", type);
            MetalInterop.set(info, "format", MetalConversions.textureFormat(format));
            MetalInterop.set(info, "usage", MetalConversions.textureUsage(usage, format));
            MetalInterop.set(info, "width", width);
            MetalInterop.set(info, "height", height);
            MetalInterop.set(info, "layer_count_or_depth", depthOrLayers);
            MetalInterop.set(info, "num_levels", mipLevels);
            MetalInterop.set(info, "sample_count", MetalInterop.sdl("SDL_GPU_SAMPLECOUNT_1"));
            this.handle = MetalInterop.sdlLong("SDL_CreateGPUTexture", device.handle(), info);
        } finally {
            MetalInterop.free(info);
        }
        if (handle == 0L) {
            throw new IllegalStateException("SDL_CreateGPUTexture failed for " + format + ": " + MetalInterop.lastSdlError());
        }
        if (label != null && !label.isBlank()) {
            try { MetalInterop.sdlCall("SDL_SetGPUTextureName", device.handle(), handle, label); } catch (Throwable ignored) {}
        }
    }

    long handle() {
        if (closed.get()) throw new IllegalStateException("Metal GPU texture is closed");
        return handle;
    }

    @Override public boolean isClosed() { return closed.get(); }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            MetalInterop.sdlCall("SDL_ReleaseGPUTexture", device.handle(), handle);
        }
    }
}

