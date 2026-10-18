package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderModule;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

/** Dependency-free reference backend for lifecycle and graph tests. */
public final class InMemoryUrdBackend implements UrdBackend {
    private static final UrdCapabilities CAPABILITIES = new UrdCapabilities(
            "reference", Set.of("triangle", "offscreen"), 8, true);

    @Override
    public String name() { return "reference"; }

    @Override
    public UrdCapabilities capabilities() { return CAPABILITIES; }

    @Override
    public UrdDevice createDevice() {
        return new Device();
    }

    private static final class Device implements UrdDevice {
        private final Set<Resource> resources = new HashSet<>();
        private boolean closed;

        @Override public UrdCapabilities capabilities() { return CAPABILITIES; }

        @Override public UrdBuffer createBuffer(long size) {
            requireDeviceOpen();
            if (size <= 0) throw new IllegalArgumentException("buffer size must be positive");
            Buffer buffer = new Buffer(size);
            resources.add(buffer);
            return buffer;
        }

        @Override public UrdTexture createTexture(int width, int height) {
            requireDeviceOpen();
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("texture dimensions must be positive");
            Texture texture = new Texture(width, height);
            resources.add(texture);
            return texture;
        }

        @Override public UrdPipeline createPipeline(ShaderModule vertex, ShaderModule fragment) {
            requireDeviceOpen();
            if (vertex == null || fragment == null || !vertex.isValid() || !fragment.isValid()) {
                throw new UrdValidationException("pipeline requires valid vertex and fragment modules");
            }
            Pipeline pipeline = new Pipeline(vertex, fragment);
            resources.add(pipeline);
            return pipeline;
        }

        @Override public UrdCommandList createCommandList() {
            requireDeviceOpen();
            return new Commands();
        }

        @Override public ByteBuffer readback(UrdTexture texture) {
            requireDeviceOpen();
            if (!(texture instanceof Texture value)) throw new UrdValidationException("foreign texture");
            value.requireOpen();
            return ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(value.width, value.height), 4));
        }

        @Override public boolean isClosed() { return closed; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            resources.forEach(Resource::close);
            resources.clear();
        }

        private void requireDeviceOpen() {
            if (closed) throw new UrdValidationException("device is closed");
        }
    }

    private abstract static class Resource implements UrdResource {
        private boolean closed;
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed = true; }
    }

    private static final class Buffer extends Resource implements UrdBuffer {
        private final long size;
        private Buffer(long size) { this.size = size; }
        @Override public long size() { requireOpen(); return size; }
        @Override public void upload(long offset, java.nio.ByteBuffer data) {
            requireOpen();
            if (offset < 0 || data == null || data.remaining() > size - offset) {
                throw new UrdValidationException("buffer upload out of bounds");
            }
        }
    }

    private static final class Texture extends Resource implements UrdTexture {
        private final int width, height;
        private Texture(int width, int height) { this.width = width; this.height = height; }
        @Override public int width() { requireOpen(); return width; }
        @Override public int height() { requireOpen(); return height; }
        @Override public void readback(CommandReadbackTarget target) {
            requireOpen();
            if (target == null) throw new IllegalArgumentException("null readback target");
            target.accept(java.nio.ByteBuffer.allocateDirect(width * height * 4));
        }
    }

    private static final class Pipeline extends Resource implements UrdPipeline {
        private final ShaderModule vertex, fragment;
        private Pipeline(ShaderModule vertex, ShaderModule fragment) { this.vertex = vertex; this.fragment = fragment; }
        @Override public ShaderModule vertexShader() { requireOpen(); return vertex; }
        @Override public ShaderModule fragmentShader() { requireOpen(); return fragment; }
    }

    private static final class Commands implements UrdCommandList {
        private boolean pass;
        private boolean submitted;
        private boolean closed;
        private UrdPipeline pipeline;
        private UrdBuffer vertexBuffer;
        @Override public void beginPass(String name, UrdTexture colorTarget) {
            requireOpen();
            if (pass) throw new UrdValidationException("nested render pass");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("blank pass name");
            if (colorTarget == null) throw new UrdValidationException("null color target");
            colorTarget.requireOpen();
            pipeline = null;
            vertexBuffer = null;
            pass = true;
        }
        @Override public void setPipeline(UrdPipeline pipeline) {
            requireOpen();
            if (!pass || pipeline == null) throw new UrdValidationException("pipeline must be set inside a pass");
            pipeline.requireOpen();
            this.pipeline = pipeline;
        }
        @Override public void setVertexBuffer(int slot, UrdBuffer buffer, long offset) {
            requireOpen();
            if (!pass || slot < 0 || buffer == null || offset < 0 || offset >= buffer.size()) {
                throw new UrdValidationException("invalid vertex buffer binding");
            }
            buffer.requireOpen();
            vertexBuffer = buffer;
        }
        @Override public void draw(int vertexCount, int firstVertex) {
            requireOpen();
            if (!pass) throw new UrdValidationException("draw outside render pass");
            if (pipeline == null || vertexBuffer == null) throw new UrdValidationException("draw state is incomplete");
            if (vertexCount <= 0 || firstVertex < 0) throw new IllegalArgumentException("invalid draw");
        }
        @Override public void endPass() {
            requireOpen();
            if (!pass) throw new UrdValidationException("no active render pass");
            pass = false;
        }
        @Override public void submit() {
            requireOpen();
            if (pass) throw new UrdValidationException("submit inside render pass");
            if (submitted) throw new UrdValidationException("command list already submitted");
            submitted = true;
        }
        @Override public boolean isSubmitted() { return submitted; }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed = true; }
        @Override public void requireOpen() { if (closed) throw new UrdValidationException("command list is closed"); }
    }
}
