package com.asbestosstar.nativeaccelerator.client;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Optional directory-prefix index over a ZipFile central directory.
 *
 * <p>This is disabled by default in pass 5 because cold-start profiling showed that eagerly building the full
 * prefix index moved too much central-directory work onto the first model query. When explicitly enabled,
 * {@code FilePackResources} normally walks the entire ZIP central directory for every namespace and
 * resource-directory query.  We scan it once per open {@link ZipFile} and append each entry to the bucket
 * for every slash-delimited directory prefix in its name.  Buckets therefore retain <em>exact central
 * directory order</em> while a later {@code assets/minecraft/models/} query is O(number of matching entries)
 * rather than O(total ZIP entries).</p>
 *
 * <p>Directory entries are retained deliberately. Vanilla {@code getNamespaces()} can observe them even
 * though {@code listResources()} later ignores them.</p>
 */
public final class ZipResourceIndex {
    private static final boolean ENABLED = NativeAcceleratorConfig.booleanValue("resource.zipIndex", false);
    private static final Map<ZipFile, Index> INDEXES = Collections.synchronizedMap(new WeakHashMap<>());

    private ZipResourceIndex() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static void clear() {
        synchronized (INDEXES) {
            INDEXES.clear();
        }
    }

    public static Enumeration<? extends ZipEntry> entries(ZipFile zip, String directoryPrefix) {
        if (!enabled()) return zip.entries();
        Index index;
        synchronized (INDEXES) {
            index = INDEXES.get(zip);
            if (index == null) {
                long started = ModelPipelineProfiler.start();
                index = new Index(zip);
                INDEXES.put(zip, index);
                ModelPipelineProfiler.record("resource.zip-index.build", System.nanoTime() - started, index.entryCount);
            } else {
                ModelPipelineProfiler.addCount("resource.zip-index.hit", 1);
            }
        }
        List<ZipEntry> matches = index.byDirectory.get(directoryPrefix);
        int count = matches == null ? 0 : matches.size();
        ModelPipelineProfiler.addCount("resource.zip-index.candidates", count);
        return matches == null ? Collections.emptyEnumeration() : Collections.enumeration(matches);
    }

    private static final class Index {
        final Map<String, List<ZipEntry>> byDirectory;
        final int entryCount;

        Index(ZipFile zip) {
            HashMap<String, ArrayList<ZipEntry>> mutable = new HashMap<>();
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            int count = 0;
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                count++;
                String name = entry.getName();
                int slash = name.indexOf('/');
                while (slash >= 0) {
                    String prefix = name.substring(0, slash + 1);
                    mutable.computeIfAbsent(prefix, ignored -> new ArrayList<>()).add(entry);
                    slash = name.indexOf('/', slash + 1);
                }
            }
            HashMap<String, List<ZipEntry>> frozen = new HashMap<>(Math.max(16, mutable.size() * 4 / 3 + 1));
            mutable.forEach((prefix, entries) -> frozen.put(prefix, List.copyOf(entries)));
            this.byDirectory = Map.copyOf(frozen);
            this.entryCount = count;
            ModelPipelineProfiler.addCount("resource.zip-index.prefix-buckets", byDirectory.size());
        }
    }
}
