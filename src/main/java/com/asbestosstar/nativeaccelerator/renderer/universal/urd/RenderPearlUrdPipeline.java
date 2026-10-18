package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderModule;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;

final class RenderPearlUrdPipeline implements UrdPipeline {
    private final ShaderModule vertex;
    private final ShaderModule fragment;
    private final CompiledRenderPipeline nativePipeline;
    private boolean closed;

    RenderPearlUrdPipeline(ShaderModule vertex, ShaderModule fragment, CompiledRenderPipeline nativePipeline) {
        this.vertex = vertex;
        this.fragment = fragment;
        this.nativePipeline = nativePipeline;
    }

    CompiledRenderPipeline nativePipeline() {
        requireOpen();
        return nativePipeline;
    }

    @Override public ShaderModule vertexShader() { requireOpen(); return vertex; }
    @Override public ShaderModule fragmentShader() { requireOpen(); return fragment; }
    @Override public boolean isClosed() { return closed; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        nativePipeline.close();
    }

    @Override public void requireOpen() {
        if (closed) throw new UrdValidationException("pipeline is closed");
    }
}
