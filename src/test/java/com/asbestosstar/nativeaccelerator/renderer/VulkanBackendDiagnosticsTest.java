package com.asbestosstar.nativeaccelerator.renderer;

import com.mojang.renderpearl.api.device.BackendCreationException;

import java.util.List;

public final class VulkanBackendDiagnosticsTest {
    public static void main(String[] args) {
        BackendCreationException missing = new BackendCreationException(
                "Device missing capabilities",
                BackendCreationException.Reason.VULKAN_MISSING_EXTENSION,
                List.of("VK_KHR_swapchain", "VK_KHR_dynamic_rendering", "VK_KHR_swapchain", " "));
        String formatted = VulkanBackendDiagnostics.format("device creation", missing);
        require(formatted.contains("reason=VULKAN_MISSING_EXTENSION"), formatted);
        require(formatted.contains("missingExtensions=[VK_KHR_dynamic_rendering, VK_KHR_swapchain]"), formatted);
        require(formatted.contains("message=\"Device missing capabilities\""), formatted);

        BackendCreationException empty = new BackendCreationException(
                null, BackendCreationException.Reason.VULKAN_DEVICE_VERSION_TOO_LOW, List.of());
        String emptyFormatted = VulkanBackendDiagnostics.format("availability probe", empty);
        require(emptyFormatted.contains("missingCapabilities=[]"), emptyFormatted);
        require(emptyFormatted.contains("message=<none>"), emptyFormatted);
        require(!emptyFormatted.contains("missingExtensions="), emptyFormatted);

        BackendCreationException other = new BackendCreationException(
                "loader unavailable", BackendCreationException.Reason.VULKAN_LOADER_MISSING);
        String otherFormatted = VulkanBackendDiagnostics.format(null, other);
        require(otherFormatted.contains("Vulkan unknown failed"), otherFormatted);
        require(otherFormatted.contains("missingCapabilities=[]"), otherFormatted);

        System.out.println("VulkanBackendDiagnosticsTest: PASS");
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
