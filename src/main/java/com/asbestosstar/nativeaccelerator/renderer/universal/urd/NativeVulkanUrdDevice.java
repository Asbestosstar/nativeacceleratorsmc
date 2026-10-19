package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererNativeApi;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderModule;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * URD device wrapping a native {@code nar_vulkan_session*} handle.
 *
 * <p>Resource creation methods (buffer, texture, pipeline, command list, readback) are stubs that
 * throw {@link UrdValidationException} until the render-command layer is implemented. Capabilities,
 * lifecycle, and tier/feature extraction are complete.</p>
 */
public final class NativeVulkanUrdDevice implements UrdDevice {
    private final RendererNativeApi api;
    private final MemorySegment sessionHandle;
    private final UrdCapabilities capabilities;
    private volatile boolean closed;

    NativeVulkanUrdDevice(RendererNativeApi api, MemorySegment sessionHandle, String backendName) {
        this.api = api;
        this.sessionHandle = sessionHandle;
        int nativeTier = api.vulkanSessionTier(sessionHandle);
        long nativeFeatures = api.vulkanSessionFeatures(sessionHandle);
        this.capabilities = buildCapabilities(backendName, nativeTier, nativeFeatures);
    }

    @Override
    public UrdCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public UrdBuffer createBuffer(long size) {
        requireDeviceOpen();
        if (!api.vulkanBufferSupported()) {
            throw new UrdValidationException(
                    "NativeVulkanUrdDevice.createBuffer: buffer ABI not present in this library build");
        }
        if (size <= 0) {
            throw new UrdValidationException("NativeVulkanUrdDevice.createBuffer: size must be positive");
        }
        MemorySegment bufferHandle = api.vulkanBufferCreate(sessionHandle, size);
        // Confirm the native size matches what was requested; use the native value as the canonical size.
        long nativeSize = api.vulkanBufferSize(bufferHandle);
        return new NativeVulkanUrdBuffer(api, sessionHandle, bufferHandle, nativeSize > 0 ? nativeSize : size);
    }

    @Override
    public UrdTexture createTexture(int width, int height) {
        requireDeviceOpen();
        if (!api.vulkanOffscreenSupported()) {
            throw new UrdValidationException(
                    "NativeVulkanUrdDevice.createTexture: offscreen ABI not present in this library build");
        }
        if (width <= 0 || height <= 0) {
            throw new UrdValidationException("NativeVulkanUrdDevice.createTexture: dimensions must be positive");
        }
        MemorySegment imageHandle = api.vulkanOffscreenImageCreate(sessionHandle, width, height);
        return new NativeVulkanUrdTexture(api, sessionHandle, imageHandle, width, height);
    }

    @Override
    public UrdPipeline createPipeline(ShaderModule vertex, ShaderModule fragment) {
        requireDeviceOpen();
        if (vertex == null || fragment == null) {
            throw new UrdValidationException("NativeVulkanUrdDevice.createPipeline: shader modules must not be null");
        }
        if (!vertex.isValid()) {
            throw new UrdValidationException("NativeVulkanUrdDevice.createPipeline: vertex shader is invalid");
        }
        if (!fragment.isValid()) {
            throw new UrdValidationException("NativeVulkanUrdDevice.createPipeline: fragment shader is invalid");
        }
        if (!api.vulkanPipelineSupported()) {
            throw new UrdValidationException(
                    "NativeVulkanUrdDevice.createPipeline: pipeline ABI not present in this library build");
        }
        return new NativeVulkanUrdPipeline(api, sessionHandle, vertex, fragment);
    }

    @Override
    public UrdCommandList createCommandList() {
        requireDeviceOpen();
        if (!api.vulkanPipelineSupported()) {
            throw new UrdValidationException(
                    "NativeVulkanUrdDevice.createCommandList: pipeline ABI not present in this library build");
        }
        return new NativeVulkanUrdCommandList(api, sessionHandle);
    }

    @Override
    public ByteBuffer readback(UrdTexture texture) {
        requireDeviceOpen();
        if (!(texture instanceof NativeVulkanUrdTexture nvt)) {
            throw new UrdValidationException(
                    "NativeVulkanUrdDevice.readback: texture must be a NativeVulkanUrdTexture owned by this device");
        }
        nvt.requireOpen();
        // Collect pixels synchronously via the offscreen readback ABI.
        final ByteBuffer[] result = {null};
        nvt.readback(pixels -> result[0] = pixels);
        return result[0];
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        api.vulkanSessionDestroy(sessionHandle);
    }

    private void requireDeviceOpen() {
        if (closed) throw new UrdValidationException("NativeVulkanUrdDevice is closed");
    }

    /** Map native tier/feature bits to a {@link UrdCapabilities} record. */
    private static UrdCapabilities buildCapabilities(String backendName, int nativeTier, long nativeFeatures) {
        Set<String> features = new LinkedHashSet<>();
        boolean usable = nativeTier >= RendererNativeApi.VULKAN_TIER_BASIC;
        if (usable) {
            features.add("vertex-fragment");
            features.add("rgba8-render-target");
            features.add("offscreen");
        }
        if ((nativeFeatures & RendererNativeApi.VULKAN_FEATURE_CLASSIC_RENDER_PASS) != 0) {
            features.add("classic-render-pass");
        }
        if ((nativeFeatures & RendererNativeApi.VULKAN_FEATURE_CPU_CULL) != 0) {
            features.add("cpu-cull");
        }
        if (nativeTier >= RendererNativeApi.VULKAN_TIER_PRESENT
                || (nativeFeatures & RendererNativeApi.VULKAN_FEATURE_SWAPCHAIN) != 0) {
            features.add("present");
        }
        if ((nativeFeatures & RendererNativeApi.VULKAN_FEATURE_DYNAMIC_RENDERING) != 0) {
            features.add("dynamic-rendering");
        }
        if ((nativeFeatures & RendererNativeApi.VULKAN_FEATURE_GPU_CULL) != 0) {
            features.add("gpu-cull");
            features.add("draw-indirect");
        }
        return new UrdCapabilities(backendName, features, 1, false);
    }
}
