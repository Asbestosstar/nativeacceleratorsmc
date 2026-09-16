package com.asbestosstar.nativeaccelerator.client;

import net.minecraft.client.resources.model.cuboid.CuboidModel;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Reload-scoped cache for unchanged model JSON parse results.
 *
 * <p>The cache is cleared before every ModelManager reload. It can therefore share immutable parsed
 * models among concurrent work in one reload without retaining data across resource-pack changes.
 * Cache misses are single-flight, so identical JSON is parsed once even when preparation workers
 * reach it concurrently.</p>
 */
public final class ModelJsonParseCache {
    private static final int DEFAULT_MAX_ENTRIES = 32_768;
    private static final ConcurrentHashMap<String, CuboidModel> MODELS = new ConcurrentHashMap<>();
    private static final LongAdder READ_NANOS = new LongAdder();
    private static final LongAdder PARSE_NANOS = new LongAdder();
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();

    private ModelJsonParseCache() {}

    /** Starts a fresh reload generation; no parsed data from a previous resource set is retained. */
    public static void beginReload() {
        MODELS.clear();
        READ_NANOS.reset();
        PARSE_NANOS.reset();
        HITS.reset();
        MISSES.reset();
    }

    /** Called in place of CuboidModel.fromStream by the ModelManager-only mixin redirect. */
    public static CuboidModel parse(Reader reader) {
        if (!com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.booleanValue("model.parseCache", true)) {
            long started = System.nanoTime();
            CuboidModel result = CuboidModel.fromStream(reader);
            PARSE_NANOS.add(System.nanoTime() - started);
            MISSES.increment();
            return result;
        }

        long readStarted = System.nanoTime();
        String json = readFully(reader);
        READ_NANOS.add(System.nanoTime() - readStarted);
        CuboidModel cached = MODELS.get(json);
        if (cached != null) {
            HITS.increment();
            return cached;
        }
        if (MODELS.size() >= maxEntries()) MODELS.clear();
        MISSES.increment();
        return MODELS.computeIfAbsent(json, ModelJsonParseCache::parseJson);
    }

    /** Snapshot used by the reload-completion profiler. Nanos are accumulated worker CPU time. */
    public static Snapshot snapshot() {
        return new Snapshot(READ_NANOS.sum(), PARSE_NANOS.sum(), HITS.sum(), MISSES.sum(), MODELS.size());
    }

    static void clearForTests() { beginReload(); }
    static int sizeForTests() { return MODELS.size(); }

    private static CuboidModel parseJson(String json) {
        long started = System.nanoTime();
        try {
            return CuboidModel.fromStream(new StringReader(json));
        } finally {
            PARSE_NANOS.add(System.nanoTime() - started);
        }
    }

    private static int maxEntries() {
        String configured = com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.stringValue("model.parseCache.maxEntries", "");
        if (configured == null || configured.isBlank()) return DEFAULT_MAX_ENTRIES;
        try { return Math.max(1, Integer.parseInt(configured)); }
        catch (NumberFormatException ignored) { return DEFAULT_MAX_ENTRIES; }
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

    public record Snapshot(long readNanos, long parseNanos, long hits, long misses, int entries) {}
}
