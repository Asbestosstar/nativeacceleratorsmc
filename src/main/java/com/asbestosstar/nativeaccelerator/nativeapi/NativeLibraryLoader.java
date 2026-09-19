package com.asbestosstar.nativeaccelerator.nativeapi;

import com.asbestosstar.nativeaccelerator.platform.LoaderEnvironment;
import com.asbestosstar.nativeaccelerator.platform.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Resolves and caches native libraries bundled inside the mod JAR.
 *
 * <p>The bundled resource is the source of truth. Its SHA-256 is calculated on every resolution and every
 * persistent candidate is verified against that digest before it can be returned. Persistent files live in
 * content-addressed directories, so changing a native binary without changing the Java/mod version can never
 * accidentally reuse an older dylib/so/dll.</p>
 *
 * <p>Two persistent caches are used:</p>
 * <ol>
 *   <li>a pack-local cache under {@code <gameDir>/.nativeaccelerator/native/};</li>
 *   <li>a user-wide OS cache, for reuse by other installations/modpacks.</li>
 * </ol>
 *
 * <p>The pack-local copy is preferred for loading. When possible it is materialized from the user cache by a
 * verified copy. Pack-local and user-wide files intentionally remain independent so damage to one cache cannot
 * corrupt the other through a shared inode. If persistent locations are unavailable, extraction falls back to a
 * process-temporary directory so native acceleration still fails open.</p>
 *
 * <p>Do not rely on only {@link Class#getResourceAsStream(String)} for reading the embedded resource. Loader
 * environments such as Fabric/Knot can load transformed classes while not exposing every {@code META-INF}
 * resource through that particular lookup route. The resource is therefore resolved through several equivalent,
 * read-only mechanisms before being declared absent.</p>
 */
public final class NativeLibraryLoader {
    private static final String MOD_ID = "nativeaccelerator";
    private static final String CACHE_DIR_NAME = ".nativeaccelerator";
    private static final String NATIVE_DIR_NAME = "native";
    private static final String USER_CACHE_OVERRIDE = "nativeaccelerator.nativeCache.userDirectory";
    private static final String PACK_CACHE_OVERRIDE = "nativeaccelerator.nativeCache.packDirectory";
    private static final int HASH_BUFFER_SIZE = 128 * 1024;

    private NativeLibraryLoader() {}

    public static Path extractBundledLibrary(String baseName) throws IOException {
        Platform platform = Platform.current();
        String fileName = Platform.mappedLibraryName(baseName);
        List<String> attempted = new ArrayList<>();

        for (String platformId : platform.nativeResourceIds()) {
            String relative = "META-INF/native/" + platformId + "/" + fileName;
            attempted.add(relative);

            byte[] bytes = readBundledResource(relative);
            if (bytes == null) {
                continue;
            }

            String sha256 = sha256(bytes);
            String digestPrefix = sha256.substring(0, 12);
            Path packRoot = packCacheRoot();
            Path userRoot = userCacheRoot(platform);
            Path packFile = cacheFile(packRoot, platformId, sha256, fileName);
            Path userFile = cacheFile(userRoot, platformId, sha256, fileName);

            // Prefer the per-pack copy. It isolates modpacks and provides a stable path for diagnostics.
            if (isVerified(packFile, sha256, bytes.length)) {
                tryPopulatePeerCache(userFile, packFile, bytes, sha256, baseName, platformId, relative);
                System.out.println("[Native Accelerator] Native cache hit (pack): " + packFile
                        + " sha256=" + digestPrefix);
                return packFile;
            }

            // Reuse a prior installation's exact same binary, then materialize the local pack copy.
            if (isVerified(userFile, sha256, bytes.length)) {
                Path local = materializePackCopy(packFile, userFile, bytes, sha256, baseName,
                        platformId, relative);
                if (local != null) {
                    System.out.println("[Native Accelerator] Native cache hit (user -> pack): " + local
                            + " sha256=" + digestPrefix);
                    return local;
                }
                System.out.println("[Native Accelerator] Native cache hit (user): " + userFile
                        + " sha256=" + digestPrefix);
                return userFile;
            }

            // Populate the user cache first so future packs can reuse it. Failure is non-fatal.
            Path stableUser = null;
            if (userFile != null) {
                try {
                    stableUser = ensureCachedFile(userFile, bytes, sha256);
                    writeMetadata(stableUser, baseName, platformId, sha256, relative);
                    System.out.println("[Native Accelerator] Native cache populated (user): " + stableUser
                            + " sha256=" + digestPrefix);
                } catch (IOException failure) {
                    System.err.println("[Native Accelerator] User native cache unavailable: " + failure.getMessage());
                }
            }

            // Prefer the pack-local active copy, linked/copied from the verified user cache when possible.
            if (packFile != null) {
                try {
                    Path local;
                    if (stableUser != null && isVerified(stableUser, sha256, bytes.length)) {
                        local = materializeVerifiedCopy(packFile, stableUser, sha256, bytes.length);
                    } else {
                        local = ensureCachedFile(packFile, bytes, sha256);
                    }
                    writeMetadata(local, baseName, platformId, sha256, relative);
                    System.out.println("[Native Accelerator] Native cache populated (pack): " + local
                            + " sha256=" + digestPrefix);
                    return local;
                } catch (IOException failure) {
                    System.err.println("[Native Accelerator] Pack native cache unavailable: " + failure.getMessage());
                }
            }

            if (stableUser != null && isVerified(stableUser, sha256, bytes.length)) {
                return stableUser;
            }

            // Last-resort compatibility path. Persistent-cache failure must never prevent the mod from starting.
            Path dir = Files.createTempDirectory("nativeaccelerator-");
            Path file = dir.resolve(fileName);
            Files.write(file, bytes);
            if (!isVerified(file, sha256, bytes.length)) {
                throw new IOException("Temporary native extraction failed SHA-256 verification for " + file);
            }
            file.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            System.out.println("[Native Accelerator] Extracted native library to temporary fallback: " + file
                    + " sha256=" + digestPrefix);
            return file;
        }

        throw new IOException("No bundled Native Accelerator library for " + platform.nativeId()
                + " (mapped name " + fileName + ", searched " + attempted + ")");
    }

    private static void tryPopulatePeerCache(Path userFile, Path packFile, byte[] bytes, String sha256,
                                             String baseName, String platformId, String relative) {
        if (userFile == null || isVerified(userFile, sha256, bytes.length)) {
            return;
        }
        try {
            Path peer;
            if (packFile != null && isVerified(packFile, sha256, bytes.length)) {
                peer = materializeVerifiedCopy(userFile, packFile, sha256, bytes.length);
            } else {
                peer = ensureCachedFile(userFile, bytes, sha256);
            }
            writeMetadata(peer, baseName, platformId, sha256, relative);
        } catch (IOException ignored) {
            // The pack-local hit is already valid; a user-wide cache is an optimization only.
        }
    }

    private static Path materializePackCopy(Path packFile, Path userFile, byte[] bytes, String sha256,
                                            String baseName, String platformId, String relative) {
        if (packFile == null) {
            return null;
        }
        try {
            Path local = materializeVerifiedCopy(packFile, userFile, sha256, bytes.length);
            writeMetadata(local, baseName, platformId, sha256, relative);
            return local;
        } catch (IOException failure) {
            System.err.println("[Native Accelerator] Could not materialize pack-local native cache: "
                    + failure.getMessage());
            return null;
        }
    }

    private static Path cacheFile(Path root, String platformId, String sha256, String fileName) {
        return root == null ? null : root.resolve(platformId).resolve(sha256).resolve(fileName);
    }

    private static Path packCacheRoot() {
        String override = System.getProperty(PACK_CACHE_OVERRIDE, "").trim();
        if (!override.isEmpty()) {
            Path configured = Path.of(override);
            Path gameDir = LoaderEnvironment.gameDirectory();
            if (!configured.isAbsolute() && gameDir != null) {
                configured = gameDir.resolve(configured);
            }
            return configured.toAbsolutePath().normalize();
        }

        Path gameDir = LoaderEnvironment.gameDirectory();
        if (gameDir == null) {
            return null;
        }
        return gameDir.toAbsolutePath().normalize().resolve(CACHE_DIR_NAME).resolve(NATIVE_DIR_NAME);
    }

    private static Path userCacheRoot(Platform platform) {
        String override = System.getProperty(USER_CACHE_OVERRIDE, "").trim();
        if (!override.isEmpty()) {
            return Path.of(override).toAbsolutePath().normalize();
        }

        String home = System.getProperty("user.home", "").trim();
        Path homePath = home.isEmpty() ? null : Path.of(home).toAbsolutePath().normalize();
        String os = platform.os().toLowerCase(Locale.ROOT);

        if (os.equals("macos")) {
            return homePath == null ? null
                    : homePath.resolve("Library").resolve("Caches").resolve("NativeAccelerator")
                    .resolve(NATIVE_DIR_NAME);
        }

        if (os.equals("windows")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData).toAbsolutePath().normalize()
                        .resolve("NativeAccelerator").resolve("Cache").resolve(NATIVE_DIR_NAME);
            }
            return homePath == null ? null
                    : homePath.resolve("AppData").resolve("Local").resolve("NativeAccelerator")
                    .resolve("Cache").resolve(NATIVE_DIR_NAME);
        }

        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg).toAbsolutePath().normalize().resolve("nativeaccelerator")
                    .resolve(NATIVE_DIR_NAME);
        }
        return homePath == null ? null
                : homePath.resolve(".cache").resolve("nativeaccelerator").resolve(NATIVE_DIR_NAME);
    }

    /**
     * Create/replace a content-addressed target from bundled bytes, verify the staging file, and publish it
     * atomically whenever the filesystem supports atomic rename.
     */
    private static Path ensureCachedFile(Path target, byte[] bytes, String sha256) throws IOException {
        if (isVerified(target, sha256, bytes.length)) {
            return target;
        }
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".tmp");
        boolean published = false;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            if (!isVerified(temp, sha256, bytes.length)) {
                throw new IOException("Staged native cache file failed SHA-256 verification: " + temp);
            }
            atomicReplace(temp, target);
            published = true;
            if (!isVerified(target, sha256, bytes.length)) {
                throw new IOException("Published native cache file failed SHA-256 verification: " + target);
            }
            return target;
        } finally {
            if (!published) {
                Files.deleteIfExists(temp);
            }
        }
    }

    /**
     * Materialize a verified cache entry into another cache as an independent file. Do not hard-link the
     * pack and user caches: keeping separate inodes lets either cache repair the other after corruption.
     */
    private static Path materializeVerifiedCopy(Path target, Path source, String sha256, int expectedLength)
            throws IOException {
        if (!isVerified(source, sha256, expectedLength)) {
            throw new IOException("Source native cache failed SHA-256 verification: " + source);
        }
        if (isVerified(target, sha256, expectedLength)) {
            return target;
        }

        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".copy.tmp");
        boolean published = false;
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            forceFile(temp);
            if (!isVerified(temp, sha256, expectedLength)) {
                throw new IOException("Materialized native cache file failed SHA-256 verification: " + temp);
            }
            atomicReplace(temp, target);
            published = true;
            if (!isVerified(target, sha256, expectedLength)) {
                throw new IOException("Published native cache file failed SHA-256 verification: " + target);
            }
            return target;
        } finally {
            if (!published) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static boolean isVerified(Path file, String expectedSha256, int expectedLength) {
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            if (Files.size(file) != expectedLength) {
                return false;
            }
            return expectedSha256.equals(sha256(file));
        } catch (IOException failure) {
            return false;
        }
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = sha256Digest();
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[HASH_BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void writeMetadata(Path nativeFile, String baseName, String platformId, String sha256,
                                      String sourceResource) {
        if (nativeFile == null) {
            return;
        }
        Path metadata = nativeFile.resolveSibling(nativeFile.getFileName() + ".json");
        String version = NativeLibraryLoader.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = "unknown";
        }
        String json = "{\n"
                + "  \"baseName\": \"" + jsonEscape(baseName) + "\",\n"
                + "  \"fileName\": \"" + jsonEscape(nativeFile.getFileName().toString()) + "\",\n"
                + "  \"sha256\": \"" + sha256 + "\",\n"
                + "  \"platform\": \"" + jsonEscape(platformId) + "\",\n"
                + "  \"modVersion\": \"" + jsonEscape(version) + "\",\n"
                + "  \"source\": \"" + jsonEscape(sourceResource) + "\"\n"
                + "}\n";
        try {
            Files.createDirectories(metadata.getParent());
            Path temp = Files.createTempFile(metadata.getParent(), "." + metadata.getFileName() + ".", ".tmp");
            try {
                Files.writeString(temp, json, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                atomicReplace(temp, metadata);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ignored) {
            // Metadata is diagnostic only. The native file hash is always checked independently.
        }
    }

    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static byte[] readBundledResource(String relative) throws IOException {
        String absolute = "/" + relative;

        // Normal JVM/Class resource path.
        try (InputStream in = NativeLibraryLoader.class.getResourceAsStream(absolute)) {
            if (in != null) {
                return in.readAllBytes();
            }
        }

        // Explicit defining-class loader lookup. This is distinct on some game loaders.
        ClassLoader definingLoader = NativeLibraryLoader.class.getClassLoader();
        if (definingLoader != null) {
            try (InputStream in = definingLoader.getResourceAsStream(relative)) {
                if (in != null) {
                    return in.readAllBytes();
                }
            }
        }

        // Thread context loader may own mod resources even when the transformed class loader does not.
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null && contextLoader != definingLoader) {
            try (InputStream in = contextLoader.getResourceAsStream(relative)) {
                if (in != null) {
                    return in.readAllBytes();
                }
            }
        }

        // Loader-neutral Fabric fallback. Reflection is intentional: nativeapi remains usable by
        // Forge/NeoForge/standalone tests without a compile-time Fabric API dependency.
        byte[] fabricBytes = readFromFabricModRoots(relative);
        if (fabricBytes != null) {
            return fabricBytes;
        }

        // Last resort: inspect the code-source path of this class directly. This handles ordinary
        // jars/directories and is useful outside Fabric as well.
        return readFromCodeSource(relative);
    }

    private static byte[] readFromFabricModRoots(String relative) {
        try {
            Class<?> fabricLoaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader", false,
                    NativeLibraryLoader.class.getClassLoader());
            Method getInstance = fabricLoaderClass.getMethod("getInstance");
            Object loader = getInstance.invoke(null);
            Method getModContainer = fabricLoaderClass.getMethod("getModContainer", String.class);
            Object optionalObject = getModContainer.invoke(loader, MOD_ID);
            if (!(optionalObject instanceof Optional<?> optional) || optional.isEmpty()) {
                return null;
            }

            Object container = optional.get();
            Method getRootPaths = container.getClass().getMethod("getRootPaths");
            Object rootsObject = getRootPaths.invoke(container);
            if (!(rootsObject instanceof Iterable<?> roots)) {
                return null;
            }

            for (Object rootObject : roots) {
                if (!(rootObject instanceof Path root)) {
                    continue;
                }
                Path candidate = root.resolve(relative);
                if (Files.isRegularFile(candidate)) {
                    System.out.println("[Native Accelerator] Native resource resolved from Fabric mod root: " + candidate);
                    return Files.readAllBytes(candidate);
                }
            }
        } catch (ClassNotFoundException ignored) {
            // Not running under Fabric.
        } catch (Throwable failure) {
            System.err.println("[Native Accelerator] Fabric native-resource fallback failed: " + failure);
        }
        return null;
    }

    private static byte[] readFromCodeSource(String relative) {
        try {
            CodeSource codeSource = NativeLibraryLoader.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return null;
            }
            URL location = codeSource.getLocation();
            URI uri = location.toURI();
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }

            Path source = Path.of(uri);
            if (Files.isDirectory(source)) {
                Path candidate = source.resolve(relative);
                if (Files.isRegularFile(candidate)) {
                    System.out.println("[Native Accelerator] Native resource resolved from code-source directory: " + candidate);
                    return Files.readAllBytes(candidate);
                }
                return null;
            }

            if (Files.isRegularFile(source)) {
                try (JarFile jar = new JarFile(source.toFile())) {
                    JarEntry entry = jar.getJarEntry(relative);
                    if (entry == null) {
                        return null;
                    }
                    System.out.println("[Native Accelerator] Native resource resolved from code-source JAR: "
                            + source + "!/" + relative);
                    try (InputStream in = jar.getInputStream(entry)) {
                        return in.readAllBytes();
                    }
                }
            }
        } catch (IOException | URISyntaxException failure) {
            System.err.println("[Native Accelerator] Code-source native-resource fallback failed: " + failure);
        }
        return null;
    }
}
