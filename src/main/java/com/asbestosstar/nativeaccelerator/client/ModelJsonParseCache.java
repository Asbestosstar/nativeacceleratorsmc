package com.asbestosstar.nativeaccelerator.client;

import net.minecraft.client.resources.model.cuboid.CuboidModel;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local cache for unchanged model JSON parse results. */
public final class ModelJsonParseCache {
    private static final String ENABLED_PROPERTY = "nativeaccelerator.model.parseCache";
    private static final String MAX_ENTRIES_PROPERTY = "nativeaccelerator.model.parseCache.maxEntries";
    private static final int DEFAULT_MAX_ENTRIES = 32_768;
    private static final ConcurrentHashMap<String, CuboidModel> MODELS = new ConcurrentHashMap<>();

    private ModelJsonParseCache() {}

    /** Called in place of CuboidModel.fromStream by the ModelManager-only mixin redirect. */
    public static CuboidModel parse(Reader reader) {
        if (!com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.booleanValue("model.parseCache", true)) return CuboidModel.fromStream(reader);
        String json = readFully(reader);
        CuboidModel hit = MODELS.get(json);
        if (hit != null) return hit;
        if (MODELS.size() >= maxEntries()) MODELS.clear();
        CuboidModel parsed = CuboidModel.fromStream(new StringReader(json));
        CuboidModel existing = MODELS.putIfAbsent(json, parsed);
        return existing != null ? existing : parsed;
    }

    static void clearForTests() { MODELS.clear(); }
    static int sizeForTests() { return MODELS.size(); }

    private static int maxEntries() {
        String configured = com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.stringValue("model.parseCache.maxEntries", "");
        if (configured == null || configured.isBlank()) return DEFAULT_MAX_ENTRIES;
        try { return Math.max(1, Integer.parseInt(configured)); }
        catch (NumberFormatException ignored) { return DEFAULT_MAX_ENTRIES;}
    }

    private static String readFully(Reader reader) {
        StringBuilder result = new StringBuilder(512);
        char[] buffer = new char[4096];
        try {
            int read;
            while ((read = reader.read(buffer)) >= 0) result.append(buffer, 0, read);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read model JSON", exception);
        }
        return result.toString();
    }
}
