package com.asbestosstar.nativeaccelerator.platform;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The single place in the universal JAR that knows mod-loader identities.
 *
 * <p>Native Accelerator ships one JAR for Fabric, Forge, NeoForge and FeatureCreep, so loader knowledge
 * is kept to this one small class. Loader class names are only ever referenced here as strings and
 * resolved with a non-initializing {@link Class#forName(String, boolean, ClassLoader)}; no shared code
 * links against a loader API. That is what keeps the per-loader entrypoints trivial and removes any need
 * for per-loader mixin configuration.</p>
 *
 * <p>This is deliberately early-startup safe: the mixin config plugin runs before Minecraft or any mod
 * loader entrypoint is initialized, so nothing here may depend on initialized game state.</p>
 */
public final class LoaderEnvironment {

    /** Loader identities understood by the shared layer. */
    public enum Loader {
        FABRIC,
        FORGE,
        NEOFORGE,
        FEATURECREEP,
        UNKNOWN;

        /** Stable lower-case id used in properties, package names and diagnostics. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Anchor classes used only as presence probes, never as types. */
    public static final String FABRIC_ANCHOR = "net.fabricmc.loader.api.FabricLoader";
    public static final String FORGE_ANCHOR = "net.minecraftforge.fml.loading.FMLLoader";
    public static final String NEOFORGE_ANCHOR = "net.neoforged.fml.loading.FMLLoader";
    public static final String FEATURECREEP_ANCHOR = "featurecreep.loader.FCLoaderBasic";

    private static final String FORGE_PATHS = "net.minecraftforge.fml.loading.FMLPaths";
    private static final String NEOFORGE_PATHS = "net.neoforged.fml.loading.FMLPaths";

    private LoaderEnvironment() {}

    /**
     * The loader currently running, or {@link Loader#UNKNOWN} outside a mod loader. Detection order puts
     * FeatureCreep and the NeoForge namespace before the shared Forge one, because NeoForge keeps the
     * {@code net.minecraftforge} names around without being Forge.
     */
    public static Loader current() {
        ClassLoader loader = contextClassLoader();
        if (present(FEATURECREEP_ANCHOR, loader)) return Loader.FEATURECREEP;
        if (present(NEOFORGE_ANCHOR, loader)) return Loader.NEOFORGE;
        if (present(FORGE_ANCHOR, loader)) return Loader.FORGE;
        if (present(FABRIC_ANCHOR, loader)) return Loader.FABRIC;
        return Loader.UNKNOWN;
    }

    /** Lower-case loader id, for example {@code neoforge}, or {@code unknown}. */
    public static String id() {
        return current().id();
    }

    /**
     * Ask the running loader where Minecraft keeps its files, without hard-depending on any loader.
     * Returns {@code null} when no loader is present or the loader does not expose a directory.
     */
    public static Path gameDirectory() {
        Path fabric = fabricGameDirectory();
        if (fabric != null) return fabric;
        Path forge = fmlGameDirectory(FORGE_PATHS);
        if (forge != null) return forge;
        return fmlGameDirectory(NEOFORGE_PATHS);
    }

    /** Non-initializing class lookup, safe before the game or a loader entrypoint starts. */
    public static boolean present(String className) {
        return present(className, contextClassLoader());
    }

    public static ClassLoader contextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : LoaderEnvironment.class.getClassLoader();
    }

    private static boolean present(String className, ClassLoader loader) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Path fabricGameDirectory() {
        try {
            Class<?> loaderClass = Class.forName(FABRIC_ANCHOR, false, contextClassLoader());
            Object instance = loaderClass.getMethod("getInstance").invoke(null);
            Object value = loaderClass.getMethod("getGameDir").invoke(instance);
            return value instanceof Path path ? path : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Path fmlGameDirectory(String className) {
        try {
            Class<?> clazz = Class.forName(className, false, contextClassLoader());
            Object value = clazz.getField("GAMEDIR").get(null);
            if (value instanceof Path path) return path;
            if (value instanceof Supplier<?> supplier && supplier.get() instanceof Path path) return path;
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
