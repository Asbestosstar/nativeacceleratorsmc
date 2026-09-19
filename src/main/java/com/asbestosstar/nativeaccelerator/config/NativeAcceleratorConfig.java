package com.asbestosstar.nativeaccelerator.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Optional runtime settings from etc/nativeaccelerator.properties. */
public final class NativeAcceleratorConfig {
    public static final String FILE_NAME = "nativeaccelerator.properties";
    private static final Properties VALUES = load();

    private NativeAcceleratorConfig() {}

    public static boolean booleanValue(String key, boolean defaultValue) {
        String value = value(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    public static int intValue(String key, int defaultValue, int minimum) {
        String value = value(key);
        if (value == null || value.isBlank()) return defaultValue;
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

    private static String value(String key) {
        String systemValue = System.getProperty("nativeaccelerator." + key);
        return systemValue != null ? systemValue : VALUES.getProperty(key);
    }

    private static Properties load() {
        Properties values = new Properties();
        Path path = Path.of(System.getProperty("user.dir", "."), "etc", FILE_NAME);
        if (!Files.isRegularFile(path)) return values;
        try (InputStream input = Files.newInputStream(path)) {
            values.load(input);
        } catch (IOException exception) {
            System.err.println("[Native Accelerator] Could not read " + path + ": " + exception.getMessage());
        }
        return values;
    }
}

