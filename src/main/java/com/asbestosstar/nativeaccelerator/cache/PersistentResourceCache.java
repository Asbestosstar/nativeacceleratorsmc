package com.asbestosstar.nativeaccelerator.cache;

import com.asbestosstar.nativeaccelerator.client.ModelPipelineProfiler;
import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent resource-cache coordinator guarded by {@link PersistentCacheEnvironment}.
 *
 * <p>Pass 5 deliberately makes source-byte caching conservative. The expensive raw-model/blockstate/item
 * parsers are already fast, so source bytes are disabled by default. If explicitly enabled, cache hits are
 * still served synchronously, but misses stream directly from Minecraft while a tee captures the bytes and
 * defers the disk write until after the reload/startup critical path.</p>
 */
public final class PersistentResourceCache {
    private static final boolean CACHE_ENABLED = NativeAcceleratorConfig.booleanValue("cache.enabled", true);
    private static final boolean SOURCE_BYTES_ENABLED = NativeAcceleratorConfig.booleanValue("cache.resourceBytes", false);
    private static final boolean SOURCE_BYTES_LEARN = NativeAcceleratorConfig.booleanValue("cache.resourceBytes.learn", true);
    private static final int SOURCE_CAPTURE_MAX = NativeAcceleratorConfig.intValue(
            "cache.resourceBytes.maxLearnBytes", 4 * 1024 * 1024, 1024);

    private static final Object LOCK = new Object();
    private static final AtomicBoolean SHUTDOWN_HOOK = new AtomicBoolean();
    private static volatile PersistentCacheEnvironment.Snapshot environment;
    private static volatile PersistentBlobCache blobs;
    private static volatile boolean initialized;

    private PersistentResourceCache() {}

    /** Initializes the shared generation identity even when source-byte caching itself is disabled. */
    public static void initialize() {
        if (!CACHE_ENABLED || initialized) return;
        synchronized (LOCK) {
            if (initialized) return;
            PersistentCacheEnvironment.Snapshot snapshot = PersistentCacheEnvironment.capture();
            environment = snapshot;
            initialized = true;
            if (!snapshot.enabled()) {
                System.out.println("[Native Accelerator] Persistent cache disabled: " + snapshot.disabledReason());
                return;
            }
            if (SOURCE_BYTES_ENABLED) {
                try {
                    blobs = new PersistentBlobCache(snapshot.generationDirectory(), "resources");
                } catch (IOException exception) {
                    blobs = null;
                    System.err.println("[Native Accelerator] Persistent source-byte cache unavailable: "
                            + exception.getMessage());
                }
            }
            System.out.println("[Native Accelerator] Persistent cache generation "
                    + snapshot.generation().substring(0, 12)
                    + " (mods=" + snapshot.mods().entryCount()
                    + ", resourcepacks=" + snapshot.resourcepacks().entryCount()
                    + ", source-bytes=" + (blobs == null ? "off" : blobs.size() + " entries") + ")");
            installShutdownHook();
        }
    }

    /** Revalidates mods/resourcepacks before each resource reload. */
    public static void beginReload() {
        initialize();
        PersistentCacheEnvironment.Snapshot snapshot = environment;
        if (snapshot == null || !snapshot.enabled()) return;
        if (PersistentCacheEnvironment.stillMatches(snapshot)) return;
        synchronized (LOCK) {
            if (environment != snapshot) return;
            DeferredCacheWriter.discardPending();
            closeCurrent();
            environment = null;
            blobs = null;
            initialized = false;
            System.out.println("[Native Accelerator] mods/resourcepacks environment changed; persistent cache generation invalidated");
            initialize();
        }
    }

    /** True when a valid persistent generation exists; individual cache families may still be disabled. */
    public static boolean enabled() {
        initialize();
        PersistentCacheEnvironment.Snapshot snapshot = environment;
        return snapshot != null && snapshot.enabled();
    }

    public static Reader reader(String family, Identifier resourceId, Resource resource) throws IOException {
        return new InputStreamReader(open(family, resourceId, resource), StandardCharsets.UTF_8);
    }

    public static InputStream open(String family, Identifier resourceId, Resource resource) throws IOException {
        initialize();
        PersistentBlobCache cache = blobs;
        if (cache == null || !cacheable(resource) || !familyEnabled(family)) return resource.open();
        String key = key(family, resourceId, resource.sourcePackId());
        long started = ModelPipelineProfiler.start();
        byte[] hit = cache.get(key);
        if (hit != null) {
            ModelPipelineProfiler.end("cache.resource.hit", started);
            ModelPipelineProfiler.addCount("cache.resource.hit-count", 1);
            ModelPipelineProfiler.addCount("cache.resource.hit-bytes", hit.length);
            return new ByteArrayInputStream(hit);
        }

        // Critical pass-5 behavior: a cache miss never readAllBytes() and never writes synchronously.
        ModelPipelineProfiler.addCount("cache.resource.miss-count", 1);
        InputStream original = resource.open();
        if (!SOURCE_BYTES_LEARN) return original;
        return new LearningInputStream(original, cache, key);
    }

    private static boolean familyEnabled(String family) {
        if ("textures".equals(family)) {
            // Decoded RGBA is a much higher-value texture cache than compressed PNG bytes.
            return NativeAcceleratorConfig.booleanValue("cache.resourceBytes.textures", false);
        }
        return true;
    }

    public static boolean cacheable(Resource resource) {
        if (resource == null || resource.source() == null) return false;
        Class<?> type = resource.source().getClass();
        if (!type.getName().startsWith("net.minecraft.server.packs.")) return false;
        return switch (type.getSimpleName()) {
            case "FilePackResources", "PathPackResources", "FixedPathPackResources",
                    "VanillaPackResources", "OverlayedPackResources" -> true;
            default -> false;
        };
    }

    /** Shared generation used by decoded-texture/atlas caches even when source-byte caching is off. */
    public static PersistentCacheEnvironment.Snapshot environment() {
        initialize();
        return environment;
    }

    public static boolean generationMatches(String generation) {
        PersistentCacheEnvironment.Snapshot snapshot = environment();
        return snapshot != null && snapshot.enabled() && snapshot.generation().equals(generation);
    }

    public static String key(String family, Identifier resourceId, String sourcePackId) {
        return family + '\u0000' + resourceId + '\u0000' + sourcePackId;
    }

    private static void installShutdownHook() {
        if (!SHUTDOWN_HOOK.compareAndSet(false, true)) return;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(PersistentResourceCache::closeCurrent,
                    "NativeAccelerator-PersistentCache-Close"));
        } catch (IllegalStateException | SecurityException ignored) {}
    }

    private static void closeCurrent() {
        synchronized (LOCK) {
            PersistentTextureCache.reset();
            PersistentAtlasLayoutCache.reset();
            PersistentBlobCache cache = blobs;
            if (cache != null) cache.close();
            blobs = null;
        }
    }

    /** Tee used only when source-byte learning is explicitly enabled. No disk I/O occurs in close(). */
    private static final class LearningInputStream extends FilterInputStream {
        private final PersistentBlobCache cache;
        private final String key;
        private ByteArrayOutputStream capture = new ByteArrayOutputStream(512);
        private boolean closed;

        private LearningInputStream(InputStream delegate, PersistentBlobCache cache, String key) {
            super(delegate);
            this.cache = cache;
            this.key = key;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) capture(value);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) capture(buffer, offset, count);
            return count;
        }

        private void capture(int value) {
            if (capture == null) return;
            if (capture.size() >= SOURCE_CAPTURE_MAX) {
                capture = null;
                ModelPipelineProfiler.addCount("cache.resource.learn-too-large", 1);
                return;
            }
            capture.write(value);
        }

        private void capture(byte[] buffer, int offset, int count) {
            if (capture == null) return;
            if ((long) capture.size() + count > SOURCE_CAPTURE_MAX) {
                capture = null;
                ModelPipelineProfiler.addCount("cache.resource.learn-too-large", 1);
                return;
            }
            capture.write(buffer, offset, count);
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                super.close();
            } finally {
                ByteArrayOutputStream captured = capture;
                capture = null;
                if (captured == null || captured.size() == 0 || cache.contains(key)) return;
                byte[] bytes = captured.toByteArray();
                String generation = environment == null ? "" : environment.generation();
                if (DeferredCacheWriter.submit("resource", bytes.length, () -> {
                    if (generationMatches(generation)) cache.put(key, bytes);
                })) {
                    ModelPipelineProfiler.addCount("cache.resource.learn-staged", 1);
                    ModelPipelineProfiler.addCount("cache.resource.learned-bytes", bytes.length);
                }
            }
        }
    }
}
