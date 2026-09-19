package com.asbestosstar.nativeaccelerator.client;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reload-scoped memoization for repeated FileToIdConverter queries.
 *
 * <p>The authoritative ResourceManager still resolves pack priority, filters and overrides. Pass 7 keeps the
 * timing of that work separate from the tiny memoization lookup so profiler output no longer labels pack
 * traversal as "resource.index" overhead. The returned authoritative map/list is stored directly: there is
 * no first-touch copy or sort.</p>
 */
public final class ReloadResourceIndex {
    private static final boolean ENABLED = NativeAcceleratorConfig.booleanValue("resource.index", true);
    private static final ConcurrentHashMap<Key, Object> CACHE = new ConcurrentHashMap<>();

    private ReloadResourceIndex() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static void clear() {
        CACHE.clear();
    }

    @SuppressWarnings("unchecked")
    public static Map<Identifier, Resource> resources(FileToIdConverter converter, ResourceManager manager) {
        if (!enabled()) return timedResources(converter, manager);
        Key key = new Key(manager, converter.prefix(), converter.extension(), false);
        long lookupStarted = ModelPipelineProfiler.start();
        Object existing = CACHE.get(key);
        if (lookupStarted != 0L) ModelPipelineProfiler.end("resource.index.lookup", lookupStarted);
        if (existing != null) {
            ModelPipelineProfiler.addCount("resource.index.hit", 1);
            return (Map<Identifier, Resource>) existing;
        }

        Map<Identifier, Resource> live = timedResources(converter, manager);
        Object raced = CACHE.putIfAbsent(key, live);
        if (raced != null) {
            ModelPipelineProfiler.addCount("resource.index.race-hit", 1);
            return (Map<Identifier, Resource>) raced;
        }
        ModelPipelineProfiler.addCount("resource.index.first-touch", 1);
        return live;
    }

    @SuppressWarnings("unchecked")
    public static Map<Identifier, List<Resource>> stacks(FileToIdConverter converter, ResourceManager manager) {
        if (!enabled()) return timedStacks(converter, manager);
        Key key = new Key(manager, converter.prefix(), converter.extension(), true);
        long lookupStarted = ModelPipelineProfiler.start();
        Object existing = CACHE.get(key);
        if (lookupStarted != 0L) ModelPipelineProfiler.end("resource.index.stack-lookup", lookupStarted);
        if (existing != null) {
            ModelPipelineProfiler.addCount("resource.index.stack-hit", 1);
            return (Map<Identifier, List<Resource>>) existing;
        }

        Map<Identifier, List<Resource>> live = timedStacks(converter, manager);
        Object raced = CACHE.putIfAbsent(key, live);
        if (raced != null) {
            ModelPipelineProfiler.addCount("resource.index.stack-race-hit", 1);
            return (Map<Identifier, List<Resource>>) raced;
        }
        ModelPipelineProfiler.addCount("resource.index.stack-first-touch", 1);
        return live;
    }

    private static Map<Identifier, Resource> timedResources(FileToIdConverter converter, ResourceManager manager) {
        long started = ModelPipelineProfiler.start();
        Map<Identifier, Resource> result = manager.listResources(converter.prefix(), converter::extensionMatches);
        if (started != 0L) {
            ModelPipelineProfiler.record("resource.manager.list-resources",
                    System.nanoTime() - started, Math.max(1, result.size()));
        }
        return result;
    }

    private static Map<Identifier, List<Resource>> timedStacks(FileToIdConverter converter, ResourceManager manager) {
        long started = ModelPipelineProfiler.start();
        Map<Identifier, List<Resource>> result = manager.listResourceStacks(converter.prefix(), converter::extensionMatches);
        if (started != 0L) {
            ModelPipelineProfiler.record("resource.manager.list-resource-stacks",
                    System.nanoTime() - started, Math.max(1, result.size()));
        }
        return result;
    }

    private static final class Key {
        private final ResourceManager manager;
        private final String prefix;
        private final String extension;
        private final boolean stacks;
        private final int hash;

        Key(ResourceManager manager, String prefix, String extension, boolean stacks) {
            this.manager = manager;
            this.prefix = prefix;
            this.extension = extension;
            this.stacks = stacks;
            int h = System.identityHashCode(manager);
            h = 31 * h + prefix.hashCode();
            h = 31 * h + extension.hashCode();
            this.hash = 31 * h + Boolean.hashCode(stacks);
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key k)) return false;
            return manager == k.manager && stacks == k.stacks
                    && prefix.equals(k.prefix) && extension.equals(k.extension);
        }
    }
}

