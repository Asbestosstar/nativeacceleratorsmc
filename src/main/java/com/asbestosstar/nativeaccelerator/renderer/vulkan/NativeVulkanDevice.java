package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.WeakHashMap;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Own-device RenderPearl boundary; production exposure remains readiness-gated. */
public final class NativeVulkanDevice implements GpuDeviceBackend {
    private final NativeVulkanDeviceState state;
    private final DeviceInfo info;
    private final NativeVulkanDeviceCore core;
    private NativeVulkanOwnedWsiBootstrap initialWsi;
    private long expectedWindow;
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final List<CommandEncoderBackend> commandEncoders = new ArrayList<>();
    private NativeVulkanSurface activeSurface;
    private final AtomicBoolean closed = new AtomicBoolean();


    public NativeVulkanDevice(NativeVulkanDeviceState state, DeviceInfo info) {
        throw new IllegalArgumentException("Native Vulkan device requires an owned Vulkan core");
    }

    NativeVulkanDevice(NativeVulkanDeviceState state, DeviceInfo info, NativeVulkanDeviceCore core) {
        this.state = java.util.Objects.requireNonNull(state, "state");
        this.info = java.util.Objects.requireNonNull(info, "info");
        this.core = java.util.Objects.requireNonNull(core, "core");
    }

    synchronized void attachWsi(NativeVulkanOwnedWsiBootstrap wsi) {
        requireLiveBackend();
        if (initialWsi != null) throw new IllegalStateException("Native Vulkan WSI was already attached");
        initialWsi = java.util.Objects.requireNonNull(wsi, "wsi");
        expectedWindow = wsi.windowHandle();
    }

    synchronized void detachWsi(NativeVulkanOwnedWsiBootstrap expected) {
        if (initialWsi == expected) {
            initialWsi = null;
            expectedWindow = 0L;
        }
    }

    public NativeVulkanDeviceState state() { return state; }

    @Override
    public synchronized GpuSurfaceBackend createSurface(long window, java.util.function.BooleanSupplier iconified) {
        requireLiveBackend();
        if (window == 0L) throw new IllegalArgumentException("Native Vulkan surface requires an SDL window");
        if (initialWsi == null || window != expectedWindow) {
            throw new IllegalStateException("Native Vulkan WSI was not attached to this SDL window");
        }
        NativeVulkanOwnedWsiBootstrap wsi = initialWsi;
        NativeVulkanSurface surface = new NativeVulkanSurface(core, wsi, iconified, this);
        resources.add(surface);
        return surface;
    }

    @Override public CommandEncoderBackend createCommandEncoder() {
        synchronized (this) {
            requireLiveBackend();
            return CommandEncoderProxy.create(this);
        }
    }

    static final class CommandEncoderProxy implements InvocationHandler {
        private final NativeVulkanDevice owner;

        private CommandEncoderProxy(NativeVulkanDevice owner) {
            this.owner = owner;
        }

        static CommandEncoderBackend create(NativeVulkanDevice owner) {
            CommandEncoderBackend proxy = (CommandEncoderBackend) Proxy.newProxyInstance(
                    CommandEncoderBackend.class.getClassLoader(),
                    new Class<?>[]{CommandEncoderBackend.class}, new CommandEncoderProxy(owner));
            synchronized (owner) { owner.commandEncoders.add(proxy); }
            return proxy;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "NativeVulkanCommandEncoder@" + Integer.toHexString(System.identityHashCode(proxy));
                };
            }
            return method.invoke(owner.commandEncoderForActiveFrame(), args);
        }
    }

    synchronized boolean ownsCommandEncoder(CommandEncoderBackend candidate) {
        return commandEncoders.contains(candidate);
    }

    private synchronized CommandEncoderBackend commandEncoderForActiveFrame() {
        NativeVulkanSurface surface = activeSurface;
        if (surface == null || !surface.isAcquired()
                || !surface.isFrameRecording() || !surface.isEncoderOpen()) {
            throw new IllegalStateException("Native Vulkan command encoders require an acquired surface frame");
        }
        return surface.encoder();
    }

    synchronized boolean hasActiveSurfaceFrame() {
        return activeSurface != null;
    }

    synchronized boolean ownsActiveSurfaceFrame(NativeVulkanSurface surface) {
        return activeSurface == surface;
    }

    synchronized void activateSurfaceFrame(NativeVulkanSurface surface) {
        requireLiveBackend();
        if (activeSurface != null && activeSurface != surface) {
            throw new IllegalStateException("Only one Native Vulkan surface frame may be active per device");
        }
        activeSurface = java.util.Objects.requireNonNull(surface, "surface");
    }

    synchronized void deactivateSurfaceFrame(NativeVulkanSurface surface) {
        if (activeSurface == surface) activeSurface = null;
    }

    @Override public synchronized GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min,
                                                             FilterMode mag, int maxAnisotropy, OptionalDouble maxLod) {
        requireLiveBackend();
        NativeVulkanSampler sampler = new NativeVulkanSampler(core.bootstrap(), state, u, v, min, mag,
                maxAnisotropy, maxLod);
        resources.add(sampler);
        return sampler;
    }

    @Override public synchronized GpuTexture createTexture(@Nullable String label, int usage, GpuFormat format,
                                                            int width, int height, int depthOrLayers, int mipLevels) {
        requireLiveBackend();
        NativeVulkanGpuTexture texture = new NativeVulkanGpuTexture(core.bootstrap(), state, core.allocator(),
                label, usage, format, width, height, depthOrLayers, mipLevels);
        resources.add(texture);
        return texture;
    }

    @Override public synchronized GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        requireLiveBackend();
        if (!(texture instanceof NativeVulkanGpuTexture nativeTexture)) {
            throw new IllegalArgumentException("Foreign GpuTexture passed to Native Vulkan device");
        }
        NativeVulkanTextureView view = new NativeVulkanTextureView(nativeTexture, baseMipLevel, mipLevels);
        resources.add(view);
        return view;
    }

    @Override public synchronized GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, long size) {
        requireLiveBackend();
        String resolvedLabel = label == null ? null : label.get();
        NativeVulkanGpuBuffer buffer = new NativeVulkanGpuBuffer(core.bootstrap(), state, core.allocator(), usage, size);
        resources.add(buffer);
        return buffer;
    }

    @Override public synchronized GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data) {
        requireLiveBackend();
        java.util.Objects.requireNonNull(data, "data");
        NativeVulkanGpuBuffer buffer = new NativeVulkanGpuBuffer(core.bootstrap(), state, core.allocator(), usage, data);
        resources.add(buffer);
        return buffer;
    }

    @Override public List<String> getLastDebugMessages() { return List.of(); }
    @Override public boolean isDebuggingEnabled() { return false; }

    @Override public synchronized BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo createInfo) {
        requireLiveBackend();
        java.util.Objects.requireNonNull(createInfo, "createInfo");
        return () -> {
            NativeVulkanRenderPipeline pipeline = new NativeVulkanRenderPipeline(core.bootstrap(), state, createInfo);
            synchronized (this) { resources.add(pipeline); }
            return pipeline;
        };
    }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (state) {
            for (int index = resources.size() - 1; index >= 0; index--) {
                try { resources.get(index).close(); } catch (Exception ignored) { }
            }
            resources.clear();
            activeSurface = null;
            try { if (initialWsi != null) initialWsi.close(); } catch (Exception ignored) { }
            initialWsi = null;
            if (core != null) {
                if (state.beginClose()) {
                    try { core.close(); } finally { state.finishClose(); }
                }
            } else {
                state.abort();
            }
        }
    }

    @Override public GpuQueryPool createTimestampQueryPool(int size) {
        requireLiveBackend();
        throw new UnsupportedOperationException("Timestamp query pools are not wired");
    }
    @Override public long getTimestampCalibrationOffset() {
        requireLiveBackend();
        throw new UnsupportedOperationException("Timestamp calibration is not wired");
    }
    @Override public DeviceInfo getDeviceInfo() { return info; }

    private void requireLiveBackend() {
        state.requireOpen();
        if (core == null) throw new UnsupportedOperationException("Native Vulkan device core is not initialized");
    }
}
