package com.asbestosstar.nativeaccelerator.client;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lazy per-ZipFile resource manifest used by {@code FilePackResources}.
 *
 * <p>The older implementation eagerly expanded every central-directory entry into every slash-prefix
 * bucket.  That made later lookups cheap but imposed a large cold-start allocation/string tax on the first
 * query.  Pass 7 instead performs exactly one lightweight central-directory scan per open ZipFile, retaining
 * the entry and its already-normalized name. Prefix result lists are materialized only when Minecraft
 * actually asks for that directory, and are then reused by every FileToIdConverter/listener in the reload.</p>
 *
 * <p>Central-directory order is preserved exactly. Directory entries are retained because vanilla namespace
 * discovery can observe them.  The original FilePackResources code remains responsible for path validation,
 * Identifier construction and ResourceOutput ordering; this class only narrows the Enumeration.</p>
 */
public final class ZipResourceIndex {
    private static final boolean ENABLED = NativeAcceleratorConfig.booleanValue("resource.zipIndex", true);
    private static final Map<ZipFile, Manifest> MANIFESTS = Collections.synchronizedMap(new WeakHashMap<>());

    private ZipResourceIndex() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static void clear() {
        synchronized (MANIFESTS) {
            MANIFESTS.clear();
        }
    }

    public static Enumeration<? extends ZipEntry> entries(ZipFile zip, String directoryPrefix) {
        if (!enabled()) return zip.entries();
        Manifest manifest;
        synchronized (MANIFESTS) {
            manifest = MANIFESTS.get(zip);
            if (manifest == null) {
                long started = ModelPipelineProfiler.start();
                manifest = new Manifest(zip);
                MANIFESTS.put(zip, manifest);
                if (started != 0L) {
                    ModelPipelineProfiler.record("resource.pack.zip.manifest-build",
                            System.nanoTime() - started, manifest.entries.length);
                }
                ModelPipelineProfiler.addCount("resource.pack.zip.manifest-entries", manifest.entries.length);
            } else {
                ModelPipelineProfiler.addCount("resource.pack.zip.manifest-hit", 1);
            }
        }
        return Collections.enumeration(manifest.forPrefix(directoryPrefix));
    }

    private static final class Manifest {
        final EntryRef[] entries;
        final ConcurrentHashMap<String, List<ZipEntry>> prefixes = new ConcurrentHashMap<>();

        Manifest(ZipFile zip) {
            ArrayList<EntryRef> refs = new ArrayList<>(Math.max(64, zip.size()));
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                refs.add(new EntryRef(entry.getName(), entry));
            }
            this.entries = refs.toArray(EntryRef[]::new);
        }

        List<ZipEntry> forPrefix(String prefix) {
            List<ZipEntry> existing = prefixes.get(prefix);
            if (existing != null) {
                ModelPipelineProfiler.addCount("resource.pack.zip.prefix-hit", 1);
                ModelPipelineProfiler.addCount("resource.pack.zip.candidates", existing.size());
                return existing;
            }

            long started = ModelPipelineProfiler.start();
            ArrayList<ZipEntry> matched = new ArrayList<>();
            for (EntryRef ref : entries) {
                if (ref.name.startsWith(prefix)) matched.add(ref.entry);
            }
            List<ZipEntry> frozen = List.copyOf(matched);
            List<ZipEntry> raced = prefixes.putIfAbsent(prefix, frozen);
            List<ZipEntry> result = raced == null ? frozen : raced;
            if (started != 0L) {
                ModelPipelineProfiler.record("resource.pack.zip.prefix-filter",
                        System.nanoTime() - started, entries.length);
            }
            ModelPipelineProfiler.addCount(raced == null
                    ? "resource.pack.zip.prefix-first-touch"
                    : "resource.pack.zip.prefix-race-hit", 1);
            ModelPipelineProfiler.addCount("resource.pack.zip.candidates", result.size());
            return result;
        }
    }

    private record EntryRef(String name, ZipEntry entry) {}
}

