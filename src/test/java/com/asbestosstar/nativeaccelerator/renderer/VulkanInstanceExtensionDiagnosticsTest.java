package com.asbestosstar.nativeaccelerator.renderer;

import java.util.List;
import java.util.Set;

public final class VulkanInstanceExtensionDiagnosticsTest {
    public static void main(String[] args) {
        String formatted = VulkanInstanceExtensionDiagnostics.format(
                List.of("VK_Z", "VK_A", "VK_Z", " "),
                Set.of("VK_A", "VK_B"), null);
        require(formatted.contains("requestedInstanceExtensions=[VK_A, VK_Z]"), formatted);
        require(formatted.contains("supportedInstanceExtensions=[VK_A, VK_B]"), formatted);
        require(formatted.contains("missingInstanceExtensions=[VK_Z]"), formatted);

        String unavailable = VulkanInstanceExtensionDiagnostics.format(List.of("VK_A"), null, "probe-failed");
        require(unavailable.contains("supportedInstanceExtensions=<unavailable>"), unavailable);
        require(unavailable.contains("missingInstanceExtensions=<unknown>"), unavailable);

        System.out.println("VulkanInstanceExtensionDiagnosticsTest: PASS");
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
