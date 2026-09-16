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
 * Reload-scoped cache for repeated FileToIdConverter directory enumeration.
 *
 * <p>Pass 5 stores Minecraft's authoritative result directly instead of eagerly cloning every first-touch
 * map/list. ResourceManager is immutable for the lifetime of one reload generation, and the cache is cleared
 * before the next generation is installed. This keeps reuse for genuinely repeated queries without adding a
 * full LinkedHashMap/List.copyOf pass to one-shot queries.</p>
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
        if (!enabled()) return manager.listResources(converter.prefix(), converter::extensionMatches);
        Key key = new Key(manager, converter.prefix(), converter.extension(), false);
        Object existing = CACHE.get(key);
        if (existing != null) {
            ModelPipelineProfiler.addCount("resource.index.hit", 1);
            return (Map<Identifier, Resource>) existing;
        }

        long started = ModelPipelineProfiler.start();
        Map<Identifier, Resource> live = manager.listResources(converter.prefix(), converter::extensionMatches);
        ModelPipelineProfiler.end("resource.index.authoritative", started);
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
        if (!enabled()) return manager.listResourceStacks(converter.prefix(), converter::extensionMatches);
        Key key = new Key(manager, converter.prefix(), converter.extension(), true);
        Object existing = CACHE.get(key);
        if (existing != null) {
            ModelPipelineProfiler.addCount("resource.index.stack-hit", 1);
            return (Map<Identifier, List<Resource>>) existing;
        }

        long started = ModelPipelineProfiler.start();
        Map<Identifier, List<Resource>> live = manager.listResourceStacks(converter.prefix(), converter::extensionMatches);
        ModelPipelineProfiler.end("resource.index.stack-authoritative", started);
        Object raced = CACHE.putIfAbsent(key, live);
        if (raced != null) {
            ModelPipelineProfiler.addCount("resource.index.stack-race-hit", 1);
            return (Map<Identifier, List<Resource>>) raced;
        }
        ModelPipelineProfiler.addCount("resource.index.stack-first-touch", 1);
        return live;
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
