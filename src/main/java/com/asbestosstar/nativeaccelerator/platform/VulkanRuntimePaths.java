package com.asbestosstar.nativeaccelerator.platform;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Applies launcher-provided Vulkan paths to LWJGL before RenderPearl initializes its loader.
 *
 * <p>The native companion has its own {@code dlopen} path handling, but Minecraft's RenderPearl
 * backend is loaded by LWJGL independently. This class deliberately uses only environment values
 * and system properties, so it is safe to call during the loader entrypoint before Minecraft or
 * any RenderPearl class is initialized.</p>
 */
public final class VulkanRuntimePaths {
    public static final String VULKAN_LIBRARY_PROPERTY = "org.lwjgl.vulkan.libname";
    public static final String LWJGL_LIBRARY_PATH_PROPERTY = "org.lwjgl.librarypath";
    public static final String SHADERC_LIBRARY_PROPERTY = "org.lwjgl.util.shaderc.libname";

    private static final String MC_VULKAN_LIBRARY_NAME = "MC_VULKAN_LIBRARY_NAME";
    private static final String VULKAN_LIBRARY_PATH = "VULKAN_LIBRARY_PATH";
    private static final String SHADERC_ROOT = "SHADERC_ROOT";
    private static final String MC_SHADERC_LIBRARY_NAME = "MC_SHADERC_LIBRARY_NAME";

    private VulkanRuntimePaths() {}

    /** Configure LWJGL once, preserving every explicit JVM property supplied by the launcher. */
    public static void configure() {
        Resolution resolution = resolve(System.getenv(), System.getProperties());
        if (resolution.vulkanLibraryName() != null
                && blank(System.getProperty(VULKAN_LIBRARY_PROPERTY))) {
            System.setProperty(VULKAN_LIBRARY_PROPERTY, resolution.vulkanLibraryName());
        }
        // Do not set org.lwjgl.librarypath from a Vulkan-only environment variable. That property
        // controls every LWJGL native module (GLFW, OpenAL, shaderc, etc.), not just Vulkan, so doing
        // so during a Metal/OpenGL launch can redirect unrelated libraries. Vulkan's own libname is
        // scoped to the Vulkan backend and remains safe to configure here.

        if (resolution.shadercLibraryName() != null
                && blank(System.getProperty(SHADERC_LIBRARY_PROPERTY))) {
            System.setProperty(SHADERC_LIBRARY_PROPERTY, resolution.shadercLibraryName());
        }
    }

    /**
     * Resolve values without mutating global state. Tests and diagnostics use this overload so
     * precedence can be checked without changing the process environment.
     */
    public static Resolution resolve(Map<String, String> environment, Properties properties) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(properties, "properties");

        String configuredVulkan = nonBlank(properties.getProperty(VULKAN_LIBRARY_PROPERTY));
        String configuredPath = nonBlank(properties.getProperty(LWJGL_LIBRARY_PATH_PROPERTY));
        String configuredShaderc = nonBlank(properties.getProperty(SHADERC_LIBRARY_PROPERTY));

        String launcherName = nonBlank(environment.get(MC_VULKAN_LIBRARY_NAME));
        String launcherPath = nonBlank(environment.get(VULKAN_LIBRARY_PATH));
        String vulkan = configuredVulkan;
        if (vulkan == null && launcherName != null) {
            vulkan = resolveLibraryName(launcherName, launcherPath);
        }

        String libraryPath = configuredPath != null ? configuredPath : launcherPath;

        // SHADERC_ROOT is intentionally only a hint. Upstream shaderc_shared.so is not necessarily
        // the LWJGL JNI shim, so automatic discovery would turn an optional compiler into a startup
        // failure. A launcher may opt in with an exact compatible library path instead.
        String shaderc = configuredShaderc;
        if (shaderc == null) shaderc = nonBlank(environment.get(MC_SHADERC_LIBRARY_NAME));

        return new Resolution(vulkan, libraryPath, shaderc,
                nonBlank(environment.get(SHADERC_ROOT)));
    }

    static String resolveLibraryName(String name, String directory) {
        if (name == null || name.isBlank()) return null;
        String trimmed = name.trim();
        if (isAbsolute(trimmed) || directory == null || directory.isBlank()) return trimmed;
        return Path.of(directory.trim(), trimmed).normalize().toString();
    }

    private static boolean isAbsolute(String value) {
        try {
            return Path.of(value).isAbsolute();
        } catch (RuntimeException ignored) {
            return value.startsWith("/") || value.matches("^[A-Za-z]:[\\\\/].*");
        }
    }

    private static String nonBlank(String value) {
        return blank(value) ? null : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record Resolution(String vulkanLibraryName, String libraryPath,
                             String shadercLibraryName, String shadercRootHint) {
        public boolean hasVulkan() {
            return vulkanLibraryName != null || libraryPath != null;
        }

        public boolean hasShadercHint() {
            return shadercLibraryName != null || shadercRootHint != null;
        }
    }
}
