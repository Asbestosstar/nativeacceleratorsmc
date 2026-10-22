package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;

import java.util.Locale;

/**
 * Selection policy for the complete Java/LWJGL RenderPearl adapter.
 *
 * <p>{@code auto} enables the adapter as a recovery candidate, not as the primary renderer.  The
 * readiness probe remains mandatory: a configuration value can permit an attempt, but it can never
 * turn the incomplete adapter into a usable backend.</p>
 */
public final class NativeVulkanBackendPolicy {
    public static final String ENABLE_PROPERTY = "nativeaccelerator.renderer.nativeBackend";
    public static final String BACKEND_NAME = "Native Vulkan (RenderPearl)";

    public enum Mode {
        OFF,
        FALLBACK,
        NATIVE_FIRST
    }

    private NativeVulkanBackendPolicy() {
    }

    /** Returns the configured policy, with the bundled default being fallback-only. */
    public static Mode mode() {
        String value = System.getProperty(ENABLE_PROPERTY);
        if (value == null || value.isBlank()) {
            value = NativeAcceleratorConfig.stringValue("renderer.nativeBackend", "auto");
        }
        return parseMode(value);
    }

    private static Mode parseMode(String value) {
        if (value == null) return Mode.FALLBACK;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "off", "false", "no", "0", "disabled" -> Mode.OFF;
            case "true", "on", "yes", "1", "force", "native-vulkan" -> Mode.NATIVE_FIRST;
            case "auto", "fallback", "default", "" -> Mode.FALLBACK;
            default -> Mode.OFF;
        };
    }

    /** True when the user explicitly requested Native Vulkan rather than automatic fallback. */
    public static boolean explicitlyEnabled() {
        return mode() == Mode.NATIVE_FIRST;
    }

    /** True when the native candidate may be considered after the official Vulkan backend. */
    public static boolean fallbackEnabled() {
        return mode() != Mode.OFF;
    }

    /** True when the native candidate may precede official Vulkan, subject to complete readiness. */
    public static boolean explicitNativeFirst() {
        return mode() == Mode.NATIVE_FIRST;
    }

    /**
     * Candidate eligibility.  This deliberately calls the production hard-fail gate for every mode;
     * loader presence, an offscreen device, and test properties are not sufficient evidence.
     */
    public static boolean shouldOffer() {
        return eligible();
    }

    public static boolean shouldOfferForFallback() {
        return eligible();
    }

    public static boolean shouldOfferAsPrimary() {
        return explicitNativeFirst() && eligible();
    }

    private static boolean eligible() {
        return fallbackEnabled() && NativeVulkanCapabilityProbe.isCompleteAdapterAvailable();
    }

    public static String unavailableReason() {
        if (mode() == Mode.OFF) return "disabled by " + ENABLE_PROPERTY;
        return NativeVulkanCapabilityProbe.unavailabilityReason();
    }
}
