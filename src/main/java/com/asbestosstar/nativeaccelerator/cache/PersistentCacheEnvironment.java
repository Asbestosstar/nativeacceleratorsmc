package com.asbestosstar.nativeaccelerator.cache;

import com.asbestosstar.nativeaccelerator.NativeAccelerator;
import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import com.asbestosstar.nativeaccelerator.platform.LoaderEnvironment;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Computes the persistent-cache generation identity.
 *
 * <p>Cache reuse is deliberately conservative.  The complete recursive contents of both {@code mods/}
 * and {@code resourcepacks/} participate in the generation key.  Adding, removing, renaming or changing
 * the size/mtime of any file creates a different generation before cached resource bytes are exposed.
 * This is stronger than checking only the active pack list and directly implements the project's rule
 * that a persistent resource cache is never reused after the user changes either directory.</p>
 *
 * <p>Reading every mod/resource-pack byte on every launch would consume much of the startup time the cache
 * is intended to save.  The default manifest therefore hashes stable file metadata (relative path, kind,
 * size and nanosecond mtime).  {@code -Dnativeaccelerator.cache.hashDirectoryContents=true} additionally
 * hashes file contents for users who prefer maximum tamper detection over startup throughput.</p>
 */
public final class PersistentCacheEnvironment {
    public static final int SCHEMA_VERSION = 4;
    private static final boolean ENABLED = NativeAcceleratorConfig.booleanValue("cache.enabled", true);
    private static final boolean HASH_CONTENTS = NativeAcceleratorConfig.booleanValue("cache.hashDirectoryContents", false);
    private static final boolean ALLOW_DEVELOPMENT = NativeAcceleratorConfig.booleanValue("cache.allowDevelopment", false);

    private PersistentCacheEnvironment() {}

    public static Snapshot capture() {
        if (!ENABLED) return Snapshot.disabled("cache disabled");
        Path gameDir = LoaderEnvironment.gameDirectory();
        if (gameDir == null) return Snapshot.disabled("loader game directory unavailable");
        gameDir = gameDir.toAbsolutePath().normalize();

        SourceIdentity minecraft = sourceIdentity("net.minecraft.client.Minecraft");
        SourceIdentity accelerator = sourceIdentity(NativeAccelerator.class.getName());
        if (!ALLOW_DEVELOPMENT && (minecraft.directory() || accelerator.directory())) {
            return Snapshot.disabled("development/classes directory detected");
        }

        try {
            DirectoryManifest mods = directoryManifest(gameDir.resolve("mods"));
            DirectoryManifest resourcepacks = directoryManifest(gameDir.resolve("resourcepacks"));
            String material = "schema=" + SCHEMA_VERSION + '\n'
                    + "minecraft=" + minecraft.value() + '\n'
                    + "nativeaccelerator=" + accelerator.value() + '\n'
                    + "loader=" + LoaderEnvironment.id() + '\n'
                    + "java=" + System.getProperty("java.runtime.version", "unknown") + '\n'
                    + "mods=" + mods.digest() + '\n'
                    + "resourcepacks=" + resourcepacks.digest() + '\n';
            String generation = sha256(material.getBytes(StandardCharsets.UTF_8));
            Path root = cacheRoot(gameDir);
            return new Snapshot(true, null, gameDir, root, generation, mods, resourcepacks,
                    minecraft.value(), accelerator.value());
        } catch (IOException exception) {
            return Snapshot.disabled("manifest scan failed: " + exception.getMessage());
        }
    }

    public static boolean stillMatches(Snapshot previous) {
        if (previous == null || !previous.enabled()) return false;
        Snapshot now = capture();
        return now.enabled() && previous.generation().equals(now.generation());
    }

    private static Path cacheRoot(Path gameDir) {
        String configured = NativeAcceleratorConfig.stringValue("cache.directory", "");
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.trim());
            return (path.isAbsolute() ? path : gameDir.resolve(path)).normalize();
        }
        return gameDir.resolve("cache").resolve("nativeaccelerator").normalize();
    }

    private static DirectoryManifest directoryManifest(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return new DirectoryManifest(root, 0, 0L, sha256("<missing>".getBytes(StandardCharsets.UTF_8)));
        }
        ArrayList<Entry> entries = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(root)) entries.add(entry(root, dir, attrs, 'D'));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                char kind = attrs.isSymbolicLink() ? 'L' : attrs.isRegularFile() ? 'F' : 'O';
                entries.add(entry(root, file, attrs, kind));
                return FileVisitResult.CONTINUE;
            }
        });
        entries.sort(Comparator.comparing(Entry::relativePath));
        MessageDigest digest = digest();
        long bytes = 0L;
        for (Entry entry : entries) {
            update(digest, entry.kind() + "\0" + entry.relativePath() + "\0" + entry.size() + "\0"
                    + entry.modifiedNanos() + "\n");
            bytes += Math.max(0L, entry.size());
            if (HASH_CONTENTS && entry.kind() == 'F') {
                Path file = root.resolve(entry.relativePath());
                try (var in = Files.newInputStream(file)) {
                    byte[] buffer = new byte[128 * 1024];
                    int read;
                    while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
                }
            } else if (entry.kind() == 'L') {
                try { update(digest, "->" + Files.readSymbolicLink(root.resolve(entry.relativePath())) + '\n'); }
                catch (IOException ignored) { update(digest, "-><unreadable>\n"); }
            }
        }
        return new DirectoryManifest(root, entries.size(), bytes, HexFormat.of().formatHex(digest.digest()));
    }

    private static Entry entry(Path root, Path path, BasicFileAttributes attrs, char kind) {
        String relative = root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
        long modifiedNanos;
        try { modifiedNanos = attrs.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS); }
        catch (ArithmeticException ignored) { modifiedNanos = attrs.lastModifiedTime().toMillis() * 1_000_000L; }
        return new Entry(relative, kind, attrs.isDirectory() ? 0L : attrs.size(), modifiedNanos);
    }

    private static SourceIdentity sourceIdentity(String className) {
        try {
            Class<?> type = Class.forName(className, false, LoaderEnvironment.contextClassLoader());
            var protection = type.getProtectionDomain();
            var location = protection == null || protection.getCodeSource() == null ? null
                    : protection.getCodeSource().getLocation();
            if (location == null) return new SourceIdentity(className + ":<unknown>", false);
            URI uri = location.toURI();
            if (!"file".equalsIgnoreCase(uri.getScheme())) return new SourceIdentity(className + ':' + uri, false);
            Path path = Path.of(uri).toAbsolutePath().normalize();
            boolean directory = Files.isDirectory(path);
            long size = directory ? -1L : safeSize(path);
            long mtime = safeMtime(path);
            return new SourceIdentity(className + ':' + path + ':' + size + ':' + mtime, directory);
        } catch (Throwable ignored) {
            return new SourceIdentity(className + ":<unavailable>", false);
        }
    }

    private static long safeSize(Path path) {
        try { return Files.size(path); } catch (IOException ignored) { return -1L; }
    }

    private static long safeMtime(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException ignored) { return -1L; }
    }

    private static void update(MessageDigest digest, String text) {
        digest.update(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = digest();
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record Entry(String relativePath, char kind, long size, long modifiedNanos) {}
    private record SourceIdentity(String value, boolean directory) {}

    public record DirectoryManifest(Path root, int entryCount, long totalBytes, String digest) {
        @Override public String toString() {
            return String.format(Locale.ROOT, "%s entries=%d bytes=%d sha256=%s",
                    root, entryCount, totalBytes, digest);
        }
    }

    public record Snapshot(boolean enabled, String disabledReason, Path gameDirectory, Path cacheRoot,
            String generation, DirectoryManifest mods, DirectoryManifest resourcepacks,
            String minecraftSource, String acceleratorSource) {
        static Snapshot disabled(String reason) {
            return new Snapshot(false, reason, null, null, "", null, null, "", "");
        }

        public Path generationDirectory() {
            return enabled ? cacheRoot.resolve("v" + SCHEMA_VERSION).resolve(generation) : null;
        }
    }
}
