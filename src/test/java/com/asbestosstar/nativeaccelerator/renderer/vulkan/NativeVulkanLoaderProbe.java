package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.device.BackendCreationException;

/** Loader-only diagnostic. This deliberately stops before instance creation or Metal device access. */
public final class NativeVulkanLoaderProbe {
    private NativeVulkanLoaderProbe() {}

    public static void main(String[] args) {
        try {
            int version = NativeVulkanDeviceBootstrap.probeLoaderApiVersion();
            if (version <= 0) throw new IllegalStateException("Vulkan loader reported no API version");
            System.out.printf("NativeVulkanLoaderProbe: PASS (loader initialized, API %d.%d.%d; no instance created)%n",
                    version >>> 22, (version >>> 12) & 0x3ff, version & 0xfff);
        } catch (BackendCreationException | RuntimeException | LinkageError failure) {
            System.err.println("NativeVulkanLoaderProbe: FAIL (" + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage() + ")");
            System.exit(1);
        }
    }
}
