package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

/** Identifies who owns the underlying graphics device wrapped by a unified backend. */
public enum UrdBackendOwnership {
    /** The unified adapter borrows the GpuDevice created and owned by Minecraft/RenderPearl. */
    RENDERPEARL,
    /** The unified adapter owns a Native Accelerator Vulkan session and its device. */
    NATIVE_VULKAN,
    /** A dependency-free reference backend used by tests. */
    REFERENCE
}
