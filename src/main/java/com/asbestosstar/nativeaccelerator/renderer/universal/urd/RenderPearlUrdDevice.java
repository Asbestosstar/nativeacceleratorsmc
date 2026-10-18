package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.asbestosstar.nativeaccelerator.renderer.universal.UrdShaderValidation;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderModule;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.nio.ByteBuffer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuFence;

/** URD device adapter over the GpuDevice Minecraft already created. */
public final class RenderPearlUrdDevice implements UrdDevice {
    private final GpuDevice device;
    private final UrdCapabilities capabilities;
    private final List<UrdResource> resources = new ArrayList<>();
    private boolean closed;

    public RenderPearlUrdDevice(GpuDevice device, String backendName) {
        if (device == null) throw new IllegalArgumentException("null GPU device");
        this.device = device;
        DeviceInfo info = device.getDeviceInfo();
        DeviceFeatures features = info.features();
        LinkedHashSet<String> supported = new LinkedHashSet<>();
        if (features.drawIndirect()) supported.add("draw-indirect");
        if (features.multiDrawIndirect()) supported.add("multi-draw-indirect");
        if (features.persistentMapping()) supported.add("persistent-mapping");
        if (features.shaderDrawParameters()) supported.add("shader-draw-parameters");
        supported.add("vertex-fragment");
        supported.add("rgba8-render-target");
        this.capabilities = new UrdCapabilities(backendName, supported,
                info.limits().maxColorAttachments(), device.isDebuggingEnabled());
    }

    public GpuDevice nativeDevice() {
        requireOpen();
        return device;
    }

    @Override public UrdCapabilities capabilities() {
        requireOpen();
        return capabilities;
    }

    @Override public UrdBuffer createBuffer(long size) {
        requireOpen();
        if (size <= 0) throw new IllegalArgumentException("buffer size must be positive");
        int usage = com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_VERTEX
                | com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_COPY_DST
                | com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_MAP_WRITE;
        RenderPearlUrdBuffer buffer = new RenderPearlUrdBuffer(
                device.createBuffer(() -> "Native Accelerator URD buffer", usage, size));
        resources.add(buffer);
        return buffer;
    }

    @Override public UrdTexture createTexture(int width, int height) {
        requireOpen();
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("texture dimensions must be positive");
        int usage = com.mojang.renderpearl.api.textures.GpuTexture.USAGE_RENDER_ATTACHMENT
                | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_SRC
                | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_DST;
        var texture = device.createTexture("Native Accelerator URD target", usage, GpuFormat.RGBA8_UNORM,
                width, height, 1, 1);
        try {
            RenderPearlUrdTexture result = new RenderPearlUrdTexture(texture, device.createTextureView(texture));
            resources.add(result);
            return result;
        } catch (RuntimeException ex) {
            texture.close();
            throw ex;
        }
    }

    @Override public UrdPipeline createPipeline(ShaderModule vertex, ShaderModule fragment) {
        requireOpen();
        UrdShaderValidation.validateTriangle(vertex, fragment);
        Identifier vertexId = shaderId(vertex);
        Identifier fragmentId = shaderId(fragment);
        RenderPipeline description = RenderPipeline.builder()
                .withLocation("nativeaccelerator:urd_pipeline")
                .withVertexShader(vertexId)
                .withFragmentShader(fragmentId)
                .withShaderDefine("NATIVE_ACCELERATOR_URD")
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
                        ColorTargetState.WRITE_ALL))
                .withVertexBinding(0, VertexFormat.builder(0)
                        .addAttribute("a_position", GpuFormat.RG32_FLOAT)
                        .addAttribute("a_color", GpuFormat.RGB32_FLOAT)
                        .build())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build();
        ShaderSource source = new CombinedShaderSource(vertex, fragment, vertexId, fragmentId);
        try {
            var pending = device.compilePipeline(description, source, ForkJoinPool.commonPool()).join();
            RenderPearlUrdPipeline result = new RenderPearlUrdPipeline(vertex, fragment, pending.finishCompile());
            resources.add(result);
            return result;
        } catch (RuntimeException ex) {
            throw new UrdValidationException("RenderPearl pipeline compilation failed: " + ex.getMessage());
        } finally {
            source.close();
        }
    }

    @Override public UrdCommandList createCommandList() {
        requireOpen();
        RenderPearlUrdCommandList list = new RenderPearlUrdCommandList(device.createCommandEncoder());
        resources.add(list);
        return list;
    }

    @Override public ByteBuffer readback(UrdTexture texture) {
        requireOpen();
        RenderPearlUrdTexture value = requireTexture(texture);
        int width = value.width();
        int height = value.height();
        long bytes = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
        if (bytes > Integer.MAX_VALUE) throw new UrdValidationException("readback is too large");
        GpuBuffer buffer = device.createBuffer(() -> "Native Accelerator URD readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, bytes);
        GpuFence fence = null;
        try {
            var encoder = device.createCommandEncoder();
            encoder.copyTextureToBuffer(value.nativeTexture(), buffer, 0L, () -> { }, 0);
            fence = encoder.createFence();
            encoder.submit();
            if (!fence.awaitCompletion(GpuFence.NO_TIMEOUT)) {
                throw new UrdValidationException("GPU readback fence did not complete");
            }
            try (var mapped = buffer.map(0L, bytes, true, false)) {
                ByteBuffer result = ByteBuffer.allocateDirect((int) bytes);
                result.put(mapped.data().duplicate()).flip();
                return result.asReadOnlyBuffer();
            }
        } finally {
            if (fence != null) fence.close();
            buffer.close();
        }
    }

    @Override public boolean isClosed() { return closed; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (int i = resources.size() - 1; i >= 0; i--) {
            try { resources.get(i).close(); } catch (RuntimeException ignored) { }
        }
        resources.clear();
    }

    @Override public void requireOpen() {
        if (closed) throw new UrdValidationException("device is closed");
    }

    private static RenderPearlUrdTexture requireTexture(UrdTexture texture) {
        if (!(texture instanceof RenderPearlUrdTexture value)) throw new UrdValidationException("foreign texture");
        value.requireOpen();
        return value;
    }

    private static Identifier shaderId(ShaderModule module) {
        String path = module.source().sourceName().replace(' ', '_');
        if (!path.contains(":")) path = "nativeaccelerator:" + path;
        return Identifier.parse(path);
    }

    private static final class CombinedShaderSource implements ShaderSource {
        private final ShaderModule vertex;
        private final ShaderModule fragment;
        private final Identifier vertexId;
        private final Identifier fragmentId;

        private CombinedShaderSource(ShaderModule vertex, ShaderModule fragment,
                                     Identifier vertexId, Identifier fragmentId) {
            this.vertex = vertex;
            this.fragment = fragment;
            this.vertexId = vertexId;
            this.fragmentId = fragmentId;
        }

        @Override public String getShader(Identifier id, ShaderType type) {
            if (type == ShaderType.VERTEX && id.equals(vertexId)) return vertex.source().source();
            if (type == ShaderType.FRAGMENT && id.equals(fragmentId)) return fragment.source().source();
            return null;
        }

        @Override public CachedIncludeSource getInclude(Identifier id) {
            return CachedIncludeSource.createError("URD inline shader has no include: " + id);
        }

        @Override public void close() {
        }
    }
}
