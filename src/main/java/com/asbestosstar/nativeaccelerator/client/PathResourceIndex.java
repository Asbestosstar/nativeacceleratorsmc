package com.asbestosstar.nativeaccelerator.client;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Lazy namespace manifest for jar/non-default-filesystem PathPackResources.
 *
 * <p>Vanilla's FixedPathPackResources ultimately calls PathPackResources.listPath for each requested
 * directory. On a jar filesystem that repeatedly traverses the same in-memory directory tree. We scan a
 * namespace exactly once, retain immutable (Identifier, Path, relative-path) rows, then answer models/,
 * blockstates/, items/, textures/, etc. by prefix filtering. Ordinary OS-directory packs are deliberately
 * excluded by default so a single models/ request never forces a cold walk of an entire external pack.</p>
 */
public final class PathResourceIndex {
    private static final boolean ENABLED = NativeAcceleratorConfig.booleanValue("resource.pathManifest", true);
    private static final boolean DEFAULT_FS = NativeAcceleratorConfig.booleanValue("resource.pathManifest.defaultFs", false);
    private static final ConcurrentHashMap<Key, Manifest> MANIFESTS = new ConcurrentHashMap<>();

    private PathResourceIndex() {}

    public static boolean tryList(String namespace, Path namespaceRoot, List<String> directory,
            PackResources.ResourceOutput output) {
        if (!ENABLED) return false;
        if (!DEFAULT_FS && namespaceRoot.getFileSystem() == FileSystems.getDefault()) return false;

        Key key = new Key(namespaceRoot, namespace);
        Manifest manifest = MANIFESTS.get(key);
        if (manifest == null) {
            long started = ModelPipelineProfiler.start();
            Manifest built;
            try {
                built = build(namespace, namespaceRoot);
            } catch (IOException failure) {
                ModelPipelineProfiler.addCount("resource.pack.path.manifest-error", 1);
                return false;
            }
            Manifest raced = MANIFESTS.putIfAbsent(key, built);
            manifest = raced == null ? built : raced;
            if (started != 0L) {
                ModelPipelineProfiler.record("resource.pack.path.manifest-build",
                        System.nanoTime() - started, built.rows.length);
            }
            ModelPipelineProfiler.addCount(raced == null
                    ? "resource.pack.path.manifest-first-touch"
                    : "resource.pack.path.manifest-race-hit", 1);
        } else {
            ModelPipelineProfiler.addCount("resource.pack.path.manifest-hit", 1);
        }

        String prefix = directory.isEmpty() ? "" : String.join("/", directory) + "/";
        long started = ModelPipelineProfiler.start();
        int matches = 0;
        for (Row row : manifest.rows) {
            if (!row.relativePath.startsWith(prefix)) continue;
            output.accept(row.id, IoSupplier.create(row.path));
            matches++;
        }
        if (started != 0L) {
            ModelPipelineProfiler.record("resource.pack.path.prefix-filter",
                    System.nanoTime() - started, manifest.rows.length);
        }
        ModelPipelineProfiler.addCount("resource.pack.path.candidates", matches);
        return true;
    }

    private static Manifest build(String namespace, Path root) throws IOException {
        if (!Files.exists(root)) return new Manifest(new Row[0]);
        ArrayList<Row> rows = new ArrayList<>();
        try (Stream<Path> stream = Files.find(root, Integer.MAX_VALUE,
                (path, attrs) -> attrs.isRegularFile())) {
            stream.forEach(path -> {
                if (SharedConstants.IS_RUNNING_IN_IDE
                        && path.getFileName().toString().equalsIgnoreCase(".ds_store")) return;
                String relative = slashPath(root.relativize(path));
                Identifier id = Identifier.tryBuild(namespace, relative);
                if (id == null) {
                    ModelPipelineProfiler.addCount("resource.pack.path.invalid-id", 1);
                    return;
                }
                rows.add(new Row(relative, id, path));
            });
        }
        return new Manifest(rows.toArray(Row[]::new));
    }

    private static String slashPath(Path relative) {
        int count = relative.getNameCount();
        if (count == 0) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i != 0) out.append('/');
            out.append(relative.getName(i));
        }
        return out.toString();
    }

    private record Row(String relativePath, Identifier id, Path path) {}
    private record Manifest(Row[] rows) {}

    private static final class Key {
        final Path root;
        final String namespace;
        final int hash;
        Key(Path root, String namespace) {
            this.root = root.toAbsolutePath().normalize();
            this.namespace = namespace;
            this.hash = 31 * this.root.hashCode() + namespace.hashCode();
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other instanceof Key k && root.equals(k.root) && namespace.equals(k.namespace);
        }
    }
}
