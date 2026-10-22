package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuDebugOptions;

import java.util.Set;

/**
 * Smoke test for the public-LWJGL bootstrap. It is diagnostic on hosts without a Vulkan loader/device;
 * it never treats an offscreen bootstrap as a complete RenderPearl adapter.
 */
public final class NativeVulkanDeviceBootstrapTest {
    public static void main(String[] args) {
        if (Boolean.getBoolean("nativeaccelerator.vulkan.loaderOnly")) {
            try {
                int version = NativeVulkanDeviceBootstrap.probeLoaderApiVersion();
                require(version > 0, "Vulkan loader reported no supported instance API version");
                System.out.printf("NativeVulkanDeviceBootstrapTest: PASS (loader-only API %d.%d.%d; no instance/device claim)%n",
                        version >>> 22, (version >>> 12) & 0x3ff, version & 0xfff);
            } catch (BackendCreationException failure) {
                System.err.println("NativeVulkanDeviceBootstrapTest: FAIL (" + failure.getMessage() + ")");
                System.exit(1);
            }
            return;
        }
        try {
            NativeVulkanDeviceBootstrap bootstrap = NativeVulkanDeviceBootstrap.create(
                    new GpuDebugOptions(0, false, false, false), Set.of());
            try {
                require(bootstrap.graphicsQueueFamily() >= 0, "graphics queue family missing");
                require(bootstrap.device() != null, "device handle missing");
                System.out.println("NativeVulkanDeviceBootstrapTest: PASS (loader/device bootstrap only; no WSI claim)");
            } finally {
                bootstrap.close();
                bootstrap.close();
            }
        } catch (BackendCreationException unavailable) {
            System.out.println("NativeVulkanDeviceBootstrapTest: BLOCKED/DIAGNOSTIC ["
                    + unavailable.getReason() + ": " + unavailable.getMessage() + "]");
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
