package com.asbestosstar.nativeaccelerator.renderer;

import java.util.Objects;

/** Inputs that must participate in a persisted Vulkan pipeline-cache namespace. */
public record PipelineCacheIdentity(
        String gpuVendor,
        String gpuDevice,
        String driverVersion,
        String shaderHash,
        String vertexLayout,
        String renderState,
        String acceleratorVersion) {
    public PipelineCacheIdentity {
        Objects.requireNonNull(gpuVendor);
        Objects.requireNonNull(gpuDevice);
        Objects.requireNonNull(driverVersion);
        Objects.requireNonNull(shaderHash);
        Objects.requireNonNull(vertexLayout);
        Objects.requireNonNull(renderState);
        Objects.requireNonNull(acceleratorVersion);
    }
}
