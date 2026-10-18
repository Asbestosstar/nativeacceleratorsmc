package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

final class RenderPearlUrdTexture implements UrdTexture {
    private final GpuTexture nativeTexture;
    private final GpuTextureView view;
    private boolean closed;

    RenderPearlUrdTexture(GpuTexture nativeTexture, GpuTextureView view) {
        this.nativeTexture = nativeTexture;
        this.view = view;
    }

    GpuTexture nativeTexture() { requireOpen(); return nativeTexture; }
    GpuTextureView nativeView() { requireOpen(); return view; }
    @Override public int width() { requireOpen(); return nativeTexture.getWidth(0); }
    @Override public int height() { requireOpen(); return nativeTexture.getHeight(0); }
    @Override public void readback(CommandReadbackTarget target) {
        requireOpen();
        if (target == null) throw new IllegalArgumentException("null readback target");
        throw new UnsupportedOperationException("texture readback requires an owning command encoder");
    }
    @Override public boolean isClosed() { return closed || nativeTexture.isClosed(); }
    @Override public void close() {
        if (closed) return;
        closed = true;
        view.close();
        nativeTexture.close();
    }
    @Override public void requireOpen() { if (isClosed()) throw new UrdValidationException("texture is closed"); }
}
