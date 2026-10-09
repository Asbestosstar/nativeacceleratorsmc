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
