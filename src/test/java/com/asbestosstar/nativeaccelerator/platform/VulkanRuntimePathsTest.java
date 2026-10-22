package com.asbestosstar.nativeaccelerator.platform;

import java.util.Map;
import java.util.Properties;

public final class VulkanRuntimePathsTest {
    public static void main(String[] args) {
        Properties empty = new Properties();
        VulkanRuntimePaths.Resolution absolute = VulkanRuntimePaths.resolve(
                Map.of("MC_VULKAN_LIBRARY_NAME", "/opt/vulkan/lib/sparcv9/libvulkan.so.1",
                        "VULKAN_LIBRARY_PATH", "/opt/vulkan/lib/sparcv9"), empty);
        require("/opt/vulkan/lib/sparcv9/libvulkan.so.1".equals(absolute.vulkanLibraryName()), absolute.toString());
        require("/opt/vulkan/lib/sparcv9".equals(absolute.libraryPath()), absolute.toString());

        VulkanRuntimePaths.Resolution basename = VulkanRuntimePaths.resolve(
                Map.of("MC_VULKAN_LIBRARY_NAME", "libvulkan.so.1",
                        "VULKAN_LIBRARY_PATH", "/opt/vulkan/lib/sparcv9"), empty);
        require("/opt/vulkan/lib/sparcv9/libvulkan.so.1".equals(basename.vulkanLibraryName()), basename.toString());
        require(!basename.vulkanLibraryName().equals(basename.libraryPath()), basename.toString());

        Properties explicit = new Properties();
        explicit.setProperty(VulkanRuntimePaths.VULKAN_LIBRARY_PROPERTY, "caller-vulkan");
        explicit.setProperty(VulkanRuntimePaths.LWJGL_LIBRARY_PATH_PROPERTY, "caller-path");
        explicit.setProperty(VulkanRuntimePaths.SHADERC_LIBRARY_PROPERTY, "caller-shaderc");
        VulkanRuntimePaths.Resolution preserved = VulkanRuntimePaths.resolve(
                Map.of("MC_VULKAN_LIBRARY_NAME", "/env-vulkan",
                        "VULKAN_LIBRARY_PATH", "/env-path",
                        "MC_SHADERC_LIBRARY_NAME", "/env-shaderc"), explicit);
        require("caller-vulkan".equals(preserved.vulkanLibraryName()), preserved.toString());
        require("caller-path".equals(preserved.libraryPath()), preserved.toString());
        require("caller-shaderc".equals(preserved.shadercLibraryName()), preserved.toString());

        VulkanRuntimePaths.Resolution pathOnly = VulkanRuntimePaths.resolve(
                Map.of("VULKAN_LIBRARY_PATH", "/opt/vulkan/lib"), new Properties());
        require(pathOnly.vulkanLibraryName() == null, pathOnly.toString());
        require("/opt/vulkan/lib".equals(pathOnly.libraryPath()), pathOnly.toString());

        VulkanRuntimePaths.Resolution namedInDirectory = VulkanRuntimePaths.resolve(
                Map.of("MC_VULKAN_LIBRARY_NAME", "libcustom-vulkan.so",
                        "VULKAN_LIBRARY_PATH", "/opt/vulkan/lib"), new Properties());
        require("/opt/vulkan/lib/libcustom-vulkan.so".equals(namedInDirectory.vulkanLibraryName()),
                namedInDirectory.toString());

        VulkanRuntimePaths.Resolution optional = VulkanRuntimePaths.resolve(
                Map.of("SHADERC_ROOT", "/opt/shaderc-2025.4"), new Properties());
        require(optional.shadercLibraryName() == null, optional.toString());
        require(optional.hasShadercHint(), optional.toString());
        require(VulkanRuntimePaths.resolveLibraryName("", "/tmp") == null, "empty name accepted");
        require(!VulkanRuntimePaths.resolveLibraryName("libvulkan.so", "/tmp").equals("/tmp"),
                "directory passed as library name");

        System.out.println("VulkanRuntimePathsTest: PASS");
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
