package com.asbestosstar.nativeaccelerator.platform;

import java.util.Properties;

/**
 * Process-wide, classloader-independent run-once claim.
 *
 * <p>Native Accelerator ships one JAR that Fabric, Forge, NeoForge and FeatureCreep can all load. A host
 * with more than one loader installed can therefore have more than one loader discover this same JAR and
 * invoke an entrypoint. Those entrypoints may even sit in different classloaders, in which case ordinary
 * static state is per-classloader and cannot see the second caller, which would let the mod initialize
 * twice: the native library would be extracted and loaded again, the renderer probe would run again, and
 * any hooks or logging would be duplicated.</p>
 *
 * <p>The JVM system property table is shared by every classloader, so it is used here as the single
 * cross-loader lock. A claim is published once and every later caller, no matter which loader or
 * classloader it came from, observes it and stands down.</p>
 *
 * <p>Claims are per key, so independent subsystems can each be run-once without interfering.</p>
 */
public final class ProcessInitializationGuard {

    /** Claim key for the mod own initialization. */
    public static final String MOD_INITIALIZATION = "nativeaccelerator.process.initialized";

    /**
     * Diagnostic escape hatch: set to {@code true} to allow deliberate re-runs. Off by default, because
     * the whole point of this class is that a normal launch initializes exactly once.
     */
    public static final String ALLOW_RERUN_PROPERTY = "nativeaccelerator.process.allowRerun";

    private ProcessInitializationGuard() {}

    /**
     * Claim {@code key} for the whole JVM exactly once. Returns {@code true} for the first caller and
     * {@code false} for every later caller, regardless of which classloader it is running in.
     */
    public static boolean claim(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key");
        if (Boolean.parseBoolean(System.getProperty(ALLOW_RERUN_PROPERTY, "false"))) return true;

        Properties properties = System.getProperties();
        if (properties.get(key) != null) return false;

        // The read-check-write must be atomic as a whole. The property table is a Hashtable, so taking its
        // own monitor makes this safe even when two loaders call in from different threads at once.
        synchronized (properties) {
            if (properties.get(key) != null) return false;
            properties.put(key, "claimed by " + claimerDescription());
            return true;
        }
    }

    /** True when {@code key} has already been claimed somewhere in this JVM. */
    public static boolean claimed(String key) {
        requireKey(key);
        return System.getProperties().get(key) != null;
    }

    /** Who claimed {@code key}, for diagnostics; {@code null} when it is unclaimed. */
    public static String claimDescription(String key) {
        requireKey(key);
        Object value = System.getProperties().get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** Drop a claim. Intended for tests; production startup claims once and never releases. */
    public static void release(String key) {
        requireKey(key);
        Properties properties = System.getProperties();
        synchronized (properties) {
            properties.remove(key);
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key");
    }

    /** Loader id and classloader name of the caller, recorded with the claim so collisions are reviewable. */
    private static String claimerDescription() {
        String loader;
        String classLoader;
        try {
            loader = LoaderEnvironment.current().id();
        } catch (Throwable ignored) {
            loader = "unknown";
        }
        try {
            classLoader = LoaderEnvironment.contextClassLoader().getName();
        } catch (Throwable ignored) {
            classLoader = "unknown";
        }
        return "loader=" + loader + ", classloader=" + classLoader;
    }
}

