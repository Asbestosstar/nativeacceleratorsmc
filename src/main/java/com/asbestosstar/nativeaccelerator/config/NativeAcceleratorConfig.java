package com.asbestosstar.nativeaccelerator.config;

import com.asbestosstar.nativeaccelerator.platform.LoaderEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Native Accelerator runtime configuration.
 *
 * <p>The canonical file is {@code <minecraft>/etc/nativeaccelerator.properties}.  The bundled defaults
 * resource is merged into that file on every launch, so newly-added settings become visible without
 * deleting an existing config. JVM {@code -Dnativeaccelerator.*} properties still have highest priority.
 * Values from the file are also mirrored into missing JVM properties so legacy early-startup code that
 * still reads System properties observes the same configuration.</p>
 */
public final class NativeAcceleratorConfig {
    public static final String FILE_NAME = "nativeaccelerator.properties";
    private static final int CURRENT_CONFIG_REVISION = 4;
    private static final String DEFAULTS_RESOURCE = "/nativeaccelerator-defaults.properties";
    private static final Object LOCK = new Object();
    private static final Properties DEFAULTS = loadDefaults();
    private static final Properties VALUES = loadAndMerge();

    private NativeAcceleratorConfig() {}

    /** Force class initialization early from the Mixin config plugin. */
    public static void bootstrapSystemProperties() {
        // Static initialization already loaded, merged and exported the file settings.
    }

    public static Path path() {
        Path game = LoaderEnvironment.gameDirectory();
        if (game == null) {
            String explicit = System.getProperty("minecraft.gameDir");
            if (explicit != null && !explicit.isBlank()) game = Path.of(explicit);
        }
        if (game == null) {
            String target = System.getProperty("minecraft.applet.TargetDirectory");
            if (target != null && !target.isBlank()) game = Path.of(target);
        }
        if (game == null) game = Path.of(System.getProperty("user.dir", "."));
        return game.toAbsolutePath().normalize().resolve("etc").resolve(FILE_NAME);
    }

    public static boolean booleanValue(String key, boolean defaultValue) {
        String value = value(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    public static int intValue(String key, int defaultValue, int minimum) {
        String value = value(key);
        if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value.trim())) return defaultValue;
        try {
            return Math.max(minimum, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public static String stringValue(String key, String defaultValue) {
        String value = value(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    public static String rawValue(String key) {
        return value(key);
    }

    public static void setBoolean(String key, boolean value) {
        setString(key, Boolean.toString(value));
    }

    public static void setInt(String key, int value) {
        setString(key, Integer.toString(value));
    }

    public static void setString(String key, String value) {
        synchronized (LOCK) {
            String normalized = value == null ? "" : value.trim();
            VALUES.setProperty(key, normalized);
            String systemKey = "nativeaccelerator." + key;
            if (!normalized.isBlank() && !"auto".equalsIgnoreCase(normalized)
                    && System.getProperty(systemKey) == null) {
                System.setProperty(systemKey, normalized);
            }
            saveLocked();
        }
    }

    public static Properties snapshot() {
        synchronized (LOCK) {
            Properties copy = new Properties();
            copy.putAll(VALUES);
            return copy;
        }
    }

    private static String value(String key) {
        String systemValue = System.getProperty("nativeaccelerator." + key);
        return systemValue != null ? systemValue : VALUES.getProperty(key);
    }

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream input = NativeAcceleratorConfig.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (input != null) defaults.load(input);
        } catch (IOException exception) {
            System.err.println("[Native Accelerator] Could not read bundled config defaults: " + exception.getMessage());
        }
        return defaults;
    }

    private static Properties loadAndMerge() {
        Properties values = new Properties();
        values.putAll(DEFAULTS);
        Path path = path();
        Properties disk = new Properties();
        if (Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                disk.load(input);
            } catch (IOException exception) {
                System.err.println("[Native Accelerator] Could not read " + path + ": " + exception.getMessage());
            }
        }
        migrateLegacyDefaults(disk);
        values.putAll(disk);

        // Make config-file settings visible to old early-startup paths which still read -D properties.
        for (String key : values.stringPropertyNames()) {
            String configured = values.getProperty(key, "").trim();
            String systemKey = "nativeaccelerator." + key;
            if (!configured.isBlank() && !"auto".equalsIgnoreCase(configured)
                    && System.getProperty(systemKey) == null) {
                System.setProperty(systemKey, configured);
            }
        }

        synchronized (LOCK) {
            try {
                writeProperties(path, values);
            } catch (IOException exception) {
                System.err.println("[Native Accelerator] Could not create/update " + path + ": " + exception.getMessage());
            }
        }
        System.out.println("[Native Accelerator] Config: " + path);
        return values;
    }


    /**
     * Older Metal bring-up builds intentionally shipped very conservative/debug-heavy Mac1
     * defaults. Because the config merger preserves disk values, those old defaults otherwise
     * survive forever even after the bundled defaults are corrected. Migrate only exact historic
     * default values; unusual/custom values remain untouched.
     */
    private static void migrateLegacyDefaults(Properties disk) {
        int revision = parseRevision(disk.getProperty("config.revision"));
        if (revision >= CURRENT_CONFIG_REVISION) return;

        if (revision < 3) {
            // Performance telemetry was originally on by default during Metal bring-up. It invokes
            // nanoTime/counter code in every draw/uniform/interop hot path and writes CSV reports.
            migrateExact(disk, "renderer.metal.benchmark", "true", "false");
        }

        if (revision < 4) {
            // Revisions < 3 contained rules that incorrectly set OCLP safety flags to false/2.
            // Configs that passed through that migration path have wrong values on disk; correct
            // them back to the safe defaults. Custom values (not matching the bad defaults) are
            // intentional and are left untouched by migrateExact.
            migrateExact(disk, "renderer.metal.mac1WaitForSwapchain", "false", "true");
            migrateExact(disk, "renderer.metal.mac1SerializeBufferUploads", "false", "true");
            migrateExact(disk, "renderer.metal.mac1FramesInFlight", "2", "1");
        }

        disk.setProperty("config.revision", Integer.toString(CURRENT_CONFIG_REVISION));
    }

    private static void migrateExact(Properties disk, String key, String oldValue, String newValue) {
        String current = disk.getProperty(key);
        if (current != null && current.trim().equalsIgnoreCase(oldValue)) {
            disk.setProperty(key, newValue);
            System.out.println("[Native Accelerator] Migrated legacy default " + key + "=" + oldValue
                    + " -> " + newValue);
        }
    }

    private static int parseRevision(String value) {
        if (value == null || value.isBlank()) return 0;
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException ignored) { return 0; }
    }

    private static void saveLocked() {
        try {
            writeProperties(path(), VALUES);
        } catch (IOException exception) {
            System.err.println("[Native Accelerator] Could not save " + path() + ": " + exception.getMessage());
        }
    }

    private static void writeProperties(Path path, Properties values) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temp)) {
            values.store(output,
                    "Native Accelerator configuration. All keys are emitted intentionally. "
                            + "Use JVM -Dnativeaccelerator.<key>=... to override a value for one launch.");
        }
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnavailable) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
