package com.asbestosstar.nativeaccelerator.renderer.metal;

/** Optional low-volume informational logging for the Metal backend. */
public final class MetalLogging {
    private static final String PROPERTY = "nativeaccelerator.renderer.metal.logging";

    private MetalLogging() {}

    /** Read on demand so bootstrap/configuration tests can set the JVM override before use. */
    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty(PROPERTY, "false"));
    }

    public static void info(String message) {
        if (enabled()) System.out.println("[Native Accelerator] " + message);
    }
}
