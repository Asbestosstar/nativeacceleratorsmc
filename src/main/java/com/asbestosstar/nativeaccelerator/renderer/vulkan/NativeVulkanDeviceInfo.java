package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;

import java.util.Locale;
import java.util.Set;

/**
 * Converts the Vulkan 1.0 physical-device property record into conservative RenderPearl metadata.
 *
 * <p>This class only reports facts available from the bootstrap. It does not infer optional feature
 * support from a device name, vendor, or operating system. Feature bits not enabled by the bootstrap
 * remain disabled here.</p>
 */
public final class NativeVulkanDeviceInfo {
    private static final String BACKEND_NAME = "Native Vulkan (LWJGL)";

    private NativeVulkanDeviceInfo() {
    }

    public static DeviceInfo fromBootstrap(NativeVulkanDeviceBootstrap bootstrap) {
        if (bootstrap == null) throw new NullPointerException("bootstrap");
        try (VkPhysicalDeviceProperties properties = bootstrap.properties()) {
            return fromProperties(properties, bootstrap.enabledDeviceExtensions());
        }
    }

    static DeviceInfo fromProperties(VkPhysicalDeviceProperties properties,
                                     Set<String> enabledExtensions) {
        if (properties == null) throw new NullPointerException("properties");
        Set<String> extensions = enabledExtensions == null ? Set.of() : Set.copyOf(enabledExtensions);
        VkPhysicalDeviceLimits limits = properties.limits();
        int maxAnisotropy = 1;
        int minUniformAlignment = (int) Math.max(1L, Math.min(Integer.MAX_VALUE,
                limits.minUniformBufferOffsetAlignment()));
        int maxTextureSize = Math.max(1, limits.maxImageDimension2D());
        int maxColorAttachments = Math.max(1, limits.maxColorAttachments());
        int maxIndirect = extensions.contains("VK_KHR_draw_indirect_count")
                ? Math.max(1, limits.maxDrawIndirectCount()) : 1;
        float timestampPeriod = limits.timestampPeriod();
        if (!Float.isFinite(timestampPeriod) || timestampPeriod <= 0.0f) timestampPeriod = 1.0f;

        DeviceFeatures features = new DeviceFeatures(
                false, // wireframeFillMode: not enabled by the bootstrap
                extensions.contains("VK_KHR_shader_draw_parameters"),
                false, // multiDrawDirectInterleaved
                false, // multiDrawDirectSeparate
                false, // multiDrawIndirect: no feature was enabled
                true,  // drawIndirect is Vulkan 1.0 core
                false, // nonZeroFirstInstance: no feature was enabled
                false  // persistentMapping: allocator has not negotiated mapping yet
        );
        DeviceLimits deviceLimits = new DeviceLimits(
                maxAnisotropy,
                minUniformAlignment,
                maxTextureSize,
                Integer.MAX_VALUE,
                1,
                maxColorAttachments,
                maxIndirect);
        String name = properties.deviceNameString();
        if (name == null || name.isBlank()) name = "Vulkan device";
        return new DeviceInfo(
                name,
                vendorName(properties.vendorID()),
                driverInfo(properties),
                true,
                BACKEND_NAME,
                timestampPeriod,
                deviceLimits,
                features,
                extensions,
                new HintsAndWorkarounds(false, false, false, false),
                deviceType(properties.deviceType()));
    }

    private static String vendorName(int vendorId) {
        return switch (vendorId) {
            case 0x10DE -> "NVIDIA";
            case 0x1002, 0x1022 -> "AMD";
            case 0x8086 -> "Intel";
            case 0x13B5 -> "ARM";
            case 0x5143 -> "Qualcomm";
            case 0x1010 -> "Imagination";
            case 0x106B -> "Apple";
            case 0x15AD -> "VMware";
            case 0x1414 -> "Microsoft";
            case 0x1AE0 -> "Google";
            case 0x10005 -> "Mesa";
            case 0x10000 -> "Khronos";
            default -> String.format(Locale.ROOT, "PCI vendor 0x%04X", vendorId);
        };
    }

    private static String driverInfo(VkPhysicalDeviceProperties properties) {
        int version = properties.driverVersion();
        return String.format(Locale.ROOT, "vendor-encoded driver 0x%08X; Vulkan API %d.%d.%d",
                version, apiMajor(properties.apiVersion()), apiMinor(properties.apiVersion()),
                apiPatch(properties.apiVersion()));
    }

    private static int apiMajor(int version) {
        return (version >>> 22) & 0x3FF;
    }

    private static int apiMinor(int version) {
        return (version >>> 12) & 0x3FF;
    }

    private static int apiPatch(int version) {
        return version & 0xFFF;
    }

    private static DeviceType deviceType(int value) {
        return switch (value) {
            case 1 -> DeviceType.INTEGRATED;
            case 2 -> DeviceType.DISCRETE;
            case 3 -> DeviceType.VIRTUAL;
            case 4 -> DeviceType.CPU;
            default -> DeviceType.OTHER;
        };
    }
}
