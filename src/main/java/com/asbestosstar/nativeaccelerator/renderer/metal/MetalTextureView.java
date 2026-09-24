package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTextureView;

import java.util.concurrent.atomic.AtomicBoolean;

/** SDL GPU does not expose separate texture-view objects; mip/layer selection lives on bindings/attachments. */
final class MetalTextureView extends BaseGpuTextureView {
    private final AtomicBoolean closed = new AtomicBoolean();

    MetalTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
    }

    MetalGpuTexture metalTexture() {
        if (closed.get()) throw new IllegalStateException("Metal texture view is closed");
        if (!(texture() instanceof MetalGpuTexture texture)) throw new IllegalArgumentException("Foreign texture in Metal view");
        return texture;
    }

    @Override public boolean isClosed() { return closed.get(); }
    @Override public void close() { closed.set(true); }
}

