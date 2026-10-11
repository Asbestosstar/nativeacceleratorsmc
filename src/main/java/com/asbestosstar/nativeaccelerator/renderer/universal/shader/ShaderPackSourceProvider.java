package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Opens a directory, ZIP, or JAR as a bounded read-only shader source. */
public final class ShaderPackSourceProvider {
    private ShaderPackSourceProvider() { }

    public static ShaderPackSource open(Path path) throws IOException {
        return open(path, ShaderPackReadLimits.DEFAULT);
    }

    public static ShaderPackSource open(Path path, ShaderPackReadLimits limits) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Shader pack does not exist: " + path);
        }
        if (Files.isSymbolicLink(path)) throw new IOException("shader pack root must not be symbolic link: " + path);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return new DirectorySource(path.toRealPath(LinkOption.NOFOLLOW_LINKS), limits);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("shader pack is not a regular file or directory: " + path);
        }
        return new ZipSource(path, limits);
    }

    private abstract static class BaseSource implements ShaderPackSource {
        final ShaderPackReadLimits limits;
        final List<ShaderPackDiagnostic> diagnostics = new ArrayList<>();
        final Map<String, byte[]> files = new LinkedHashMap<>();
        long totalBytes;
        BaseSource(ShaderPackReadLimits limits) { this.limits = limits; }
        @Override public Optional<byte[]> read(String requested) {
            String path = normalize(requested);
            if (path == null) return Optional.empty();
            byte[] bytes = files.get(path);
            if (bytes == null) diagnostics.add(new ShaderPackDiagnostic(ShaderPackDiagnostic.Kind.NOT_FOUND, path, "entry is absent"));
            return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
        }
        @Override public List<String> list() { return List.copyOf(new TreeSet<>(files.keySet())); }
        @Override public List<ShaderPackDiagnostic> diagnostics() { return List.copyOf(diagnostics); }
        void add(String raw, InputStream stream, long declaredSize) throws IOException {
            String path = normalize(raw);
            if (path == null) { diagnostics.add(new ShaderPackDiagnostic(ShaderPackDiagnostic.Kind.INVALID_PATH, raw, "unsafe or empty path")); return; }
            if (files.containsKey(path)) {
                throw new ShaderPackSourceDiagnosticException(new ShaderPackDiagnostic(
                        ShaderPackDiagnostic.Kind.INVALID_PATH, path, "duplicate source entry"));
            }
            if (files.size() >= limits.maxEntries()) throw limit("entry count", path);
            if (declaredSize > limits.maxFileBytes()) throw tooLarge(path, declaredSize);
            byte[] bytes = readBounded(stream, path);
            if (totalBytes > limits.maxTotalBytes() - bytes.length) throw limit("total source bytes", path);
            totalBytes += bytes.length;
            files.put(path, bytes);
        }
        private byte[] readBounded(InputStream in, String path) throws IOException {
            byte[] out = new byte[(int)Math.min(limits.maxFileBytes(), 8192)];
            int used = 0;
            while (true) {
                if (used == out.length) {
                    if (out.length >= limits.maxFileBytes()) throw tooLarge(path, limits.maxFileBytes() + 1);
                    int next = (int)Math.min(limits.maxFileBytes(), Math.max(1L, out.length * 2L));
                    byte[] expanded = new byte[next]; System.arraycopy(out, 0, expanded, 0, used); out = expanded;
                }
                int n = in.read(out, used, out.length - used);
                if (n < 0) { byte[] exact = new byte[used]; System.arraycopy(out, 0, exact, 0, used); return exact; }
                used += n;
            }
        }
        ShaderPackSourceDiagnosticException limit(String what, String path) {
            diagnostics.add(new ShaderPackDiagnostic(ShaderPackDiagnostic.Kind.LIMIT_EXCEEDED, path, what + " exceeds configured bound"));
            return new ShaderPackSourceDiagnosticException(diagnostics.get(diagnostics.size() - 1));
        }
        ShaderPackSourceDiagnosticException tooLarge(String path, long size) {
            diagnostics.add(new ShaderPackDiagnostic(ShaderPackDiagnostic.Kind.TOO_LARGE, path, "source exceeds " + limits.maxFileBytes() + " bytes (declared " + size + ")"));
            return new ShaderPackSourceDiagnosticException(diagnostics.get(diagnostics.size() - 1));
        }
    }

    private static final class DirectorySource extends BaseSource {
        DirectorySource(Path root, ShaderPackReadLimits limits) throws IOException { super(limits); scan(root, root); }
        private void scan(Path root, Path current) throws IOException {
            try (var stream = Files.list(current)) {
                for (Path p : stream.sorted().toList()) {
                    if (Files.isSymbolicLink(p)) {
                        throw new ShaderPackSourceDiagnosticException(new ShaderPackDiagnostic(
                                ShaderPackDiagnostic.Kind.INVALID_PATH, root.relativize(p).toString(),
                                "symbolic links are not allowed in shader packs"));
                    }
                    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) scan(root, p);
                    else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                        try (InputStream in = Files.newInputStream(p)) {
                            add(root.relativize(p).toString(), in, Files.size(p));
                        }
                    }
                }
            }
        }
    }

    private static final class ZipSource extends BaseSource {
        private final ZipFile zip;
        ZipSource(Path path, ShaderPackReadLimits limits) throws IOException { super(limits); zip = new ZipFile(path.toFile()); scan(); }
        private void scan() throws IOException {
            var entries = zip.stream().sorted(Comparator.comparing(ZipEntry::getName)).toList();
            for (ZipEntry e : entries) {
                if (e.isDirectory()) continue;
                if (e.getMethod() != ZipEntry.STORED && e.getCompressedSize() > 0
                        && e.getSize() > limits.maxFileBytes()
                        && e.getSize() >= 0) {
                    throw tooLarge(e.getName(), e.getSize());
                }
                try (InputStream in = zip.getInputStream(e)) { add(e.getName(), in, e.getSize()); }
            }
        }
        @Override public void close() throws IOException { zip.close(); }
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf('\0') >= 0 || raw.startsWith("/") || raw.startsWith("\\")) return null;
        String s = raw.replace('\\', '/');
        var pieces = s.split("/", -1); var clean = new ArrayList<String>(pieces.length);
        for (String piece : pieces) {
            if (piece.isEmpty() || piece.equals(".")) continue;
            if (piece.equals("..")) { if (clean.isEmpty()) return null; clean.remove(clean.size() - 1); }
            else clean.add(piece);
        }
        return clean.isEmpty() ? null : String.join("/", clean);
    }

    public static final class ShaderPackSourceDiagnosticException extends IOException {
        private final ShaderPackDiagnostic diagnostic;
        ShaderPackSourceDiagnosticException(ShaderPackDiagnostic diagnostic) { super(diagnostic.toString()); this.diagnostic = diagnostic; }
        public ShaderPackDiagnostic diagnostic() { return diagnostic; }
    }
}
