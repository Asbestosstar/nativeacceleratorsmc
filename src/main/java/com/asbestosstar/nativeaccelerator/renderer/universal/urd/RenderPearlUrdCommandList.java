package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;

import java.util.Optional;

final class RenderPearlUrdCommandList implements UrdCommandList {
    private final CommandEncoder encoder;
    private RenderPass pass;
    private boolean submitted;
    private boolean closed;

    RenderPearlUrdCommandList(CommandEncoder encoder) { this.encoder = encoder; }

    @Override public void beginPass(String name, UrdTexture colorTarget) {
        requireOpen();
        if (pass != null) throw new UrdValidationException("nested render pass");
        RenderPearlUrdTexture texture = requireTexture(colorTarget);
        pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> name)
                .withColorAttachment(texture.nativeView(), Optional.empty())
                .withRenderArea(new RenderPass.RenderArea(0, 0, texture.width(), texture.height()))
                .build());
    }

    @Override public void setPipeline(UrdPipeline pipeline) {
        requireOpen();
        if (pass == null) throw new UrdValidationException("pipeline outside render pass");
        pass.setPipeline(requirePipeline(pipeline).nativePipeline());
    }

    @Override public void setVertexBuffer(int slot, UrdBuffer buffer, long offset) {
        requireOpen();
        if (pass == null) throw new UrdValidationException("vertex buffer outside render pass");
        if (slot < 0 || slot >= RenderPass.MAX_VERTEX_BUFFERS) {
            throw new UrdValidationException("invalid vertex buffer slot: " + slot);
        }
        RenderPearlUrdBuffer value = requireBuffer(buffer);
        if (offset < 0 || offset >= value.size()) throw new IllegalArgumentException("invalid vertex offset");
        pass.setVertexBuffer(slot, new GpuBufferSlice(value.nativeBuffer(), offset, value.size() - offset));
    }

    @Override public void draw(int vertexCount, int firstVertex) {
        requireOpen();
        if (pass == null) throw new UrdValidationException("draw outside render pass");
        if (vertexCount <= 0 || firstVertex < 0) throw new IllegalArgumentException("invalid draw");
        pass.draw(vertexCount, 1, firstVertex, 0);
    }

    @Override public void endPass() {
        requireOpen();
        if (pass == null) throw new UrdValidationException("no active render pass");
        pass.close();
        pass = null;
    }

    @Override public void submit() {
        requireOpen();
        if (pass != null) throw new UrdValidationException("submit inside render pass");
        if (submitted) throw new UrdValidationException("command list already submitted");
        encoder.submit();
        submitted = true;
    }

    @Override public boolean isSubmitted() { return submitted; }
    @Override public boolean isClosed() { return closed; }
    @Override public void close() {
        if (closed) return;
        if (pass != null) {
            pass.close();
            pass = null;
        }
        closed = true;
    }

    @Override public void requireOpen() {
        if (closed) throw new UrdValidationException("command list is closed");
    }

    private static RenderPearlUrdTexture requireTexture(UrdTexture texture) {
        if (!(texture instanceof RenderPearlUrdTexture value)) throw new UrdValidationException("foreign texture");
        value.requireOpen();
        return value;
    }
    private static RenderPearlUrdBuffer requireBuffer(UrdBuffer buffer) {
        if (!(buffer instanceof RenderPearlUrdBuffer value)) throw new UrdValidationException("foreign buffer");
        value.requireOpen();
        return value;
    }
    private static RenderPearlUrdPipeline requirePipeline(UrdPipeline pipeline) {
        if (!(pipeline instanceof RenderPearlUrdPipeline value)) throw new UrdValidationException("foreign pipeline");
        value.requireOpen();
        return value;
    }
}
