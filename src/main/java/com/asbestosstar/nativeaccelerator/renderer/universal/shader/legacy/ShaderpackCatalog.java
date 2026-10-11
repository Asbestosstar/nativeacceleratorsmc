package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import com.asbestosstar.nativeaccelerator.platform.LoaderEnvironment;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackReadLimits;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSource;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSourceProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Bounded, read-only catalog of packs installed below the game's shaderpacks directory. */
public final class ShaderpackCatalog {
    public static final String SELECTED_KEY = "shaders.shaderpack";
    public static final long MAX_ENTRY_BYTES = 1L << 20;
    public static final int MAX_ENTRIES = 4096;

    private final Path gameDirectory;
    private final Path root;

    public ShaderpackCatalog() {
        this(resolveGameDirectory());
    }

    public ShaderpackCatalog(Path gameDirectory) {
        if (gameDirectory == null) throw new IllegalArgumentException("gameDirectory is null");
        this.gameDirectory = gameDirectory.toAbsolutePath().normalize();
        this.root = this.gameDirectory.resolve("shaderpacks").normalize();
    }

    public Path gameDirectory() { return gameDirectory; }
    public Path root() { return root; }

    /** Opens a selected pack through the bounded, read-only source provider. */
    public ShaderPackSource openSource(ShaderpackDescriptor descriptor) throws IOException {
        if (descriptor == null || !isSafeId(descriptor.id())) throw new IllegalArgumentException("invalid shaderpack");
        Path candidate = root.resolve(descriptor.id()).normalize();
        if (!candidate.getParent().equals(root) || !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("shaderpack is outside catalog root: " + descriptor.id());
        }
        return ShaderPackSourceProvider.open(candidate, ShaderPackReadLimits.DEFAULT);
    }

    /** Creates only the expected application directory, never arbitrary pack paths. */
    public void ensureRoot() throws IOException {
        Files.createDirectories(root);
    }

    public List<ShaderpackDescriptor> scan() throws IOException {
        ensureRoot();
        List<ShaderpackDescriptor> result = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .limit(MAX_ENTRIES)
                    .forEach(path -> result.add(describe(path)));
        }
        return List.copyOf(result);
    }

    public String selectedId() {
        String value = NativeAcceleratorConfig.stringValue(SELECTED_KEY, "");
        return isSafeId(value) ? value : "";
    }

    public void select(String id) {
        if (id == null || id.isBlank()) {
            NativeAcceleratorConfig.setString(SELECTED_KEY, "");
            return;
        }
        if (!isSafeId(id)) throw new IllegalArgumentException("shaderpack id escapes catalog root");
        NativeAcceleratorConfig.setString(SELECTED_KEY, id);
    }

    public boolean isSafeId(String id) {
        if (id == null || id.isBlank() || id.contains("/") || id.contains("\\") || id.contains("..")) return false;
        return id.equals(Path.of(id).getFileName().toString());
    }

    private ShaderpackDescriptor describe(Path path) {
        String id = path.getFileName().toString();
        ShaderpackDescriptor.Kind kind = kind(path);
        Set<ShaderTechnology> technologies = new TreeSet<>(Comparator.comparing(Enum::name));
        String reason = "No recognized shader source was found";
        ShaderpackStatus status = ShaderpackStatus.UNKNOWN;
        try {
            List<String> names = kind == ShaderpackDescriptor.Kind.DIRECTORY ? directoryNames(path) : archiveNames(path);
            for (String name : names) {
                ShaderTechnology technology = ShaderTechnology.fromFileName(name);
                if (technology != ShaderTechnology.UNKNOWN) technologies.add(technology);
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("sgix")) technologies.add(ShaderTechnology.SGIX);
            }
            if (technologies.isEmpty()) {
                status = kind == ShaderpackDescriptor.Kind.OTHER ? ShaderpackStatus.UNKNOWN : ShaderpackStatus.UNKNOWN;
            } else if (technologies.stream().anyMatch(ShaderTechnology::incomplete)) {
                status = ShaderpackStatus.INCOMPLETE;
                reason = "One or more detected technologies have no complete shaderpack runtime";
            } else if (technologies.contains(ShaderTechnology.GLSL)) {
                status = ShaderpackStatus.FRONTEND_ONLY;
                reason = "GLSL compilation exists only for the narrow URD vertical slice; pack execution is not wired";
            } else {
                status = ShaderpackStatus.UNSUPPORTED;
                reason = "Detected technology has no shaderpack execution path";
            }
        } catch (Exception error) {
            status = ShaderpackStatus.INVALID;
            reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }
        return new ShaderpackDescriptor(id, id, path, kind, technologies, status, reason, hash(path));
    }

    private static ShaderpackDescriptor.Kind kind(Path path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return ShaderpackDescriptor.Kind.DIRECTORY;
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) return ShaderpackDescriptor.Kind.ZIP;
        if (lower.endsWith(".jar")) return ShaderpackDescriptor.Kind.JAR;
        return ShaderpackDescriptor.Kind.OTHER;
    }

    private static List<String> directoryNames(Path directory) throws IOException {
        List<String> names = new ArrayList<>();
        try (var stream = Files.walk(directory, 8)) {
            stream.filter(Files::isRegularFile).limit(MAX_ENTRIES).forEach(path -> {
                Path relative = directory.relativize(path);
                if (safeRelative(relative)) names.add(relative.toString().replace(path.getFileSystem().getSeparator(), "/"));
            });
        }
        return names;
    }

    private static List<String> archiveNames(Path archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            int count = 0;
            var entries = zip.entries();
            while (entries.hasMoreElements() && count++ < MAX_ENTRIES) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && safeRelative(Path.of(name))) {
                    if (entry.getSize() >= 0 && entry.getSize() > MAX_ENTRY_BYTES) continue;
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static boolean safeRelative(Path path) {
        if (path.isAbsolute() || path.getNameCount() == 0) return false;
        for (Path part : path) if (part.toString().equals("..") || part.toString().isBlank()) return false;
        return true;
    }

    private static String hash(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (Files.isRegularFile(path)) {
                try (InputStream input = Files.newInputStream(path)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    long total = 0;
                    while ((read = input.read(buffer)) >= 0 && total < MAX_ENTRY_BYTES * 4) {
                        digest.update(buffer, 0, read);
                        total += read;
                    }
                }
            } else {
                digest.update(path.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                try (var stream = Files.walk(path, 8)) {
                    stream.filter(Files::isRegularFile).sorted().limit(MAX_ENTRIES).forEach(file -> {
                        digest.update(path.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                        try { digest.update(Files.readAllBytes(file)); } catch (IOException ignored) { digest.update((byte) 0); }
                    });
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException error) {
            return "";
        }
    }

    private static Path resolveGameDirectory() {
        Path loaderPath = LoaderEnvironment.gameDirectory();
        if (loaderPath != null) return loaderPath;
        String explicit = System.getProperty("minecraft.gameDir");
        if (explicit != null && !explicit.isBlank()) return Path.of(explicit);
        return Path.of(System.getProperty("user.dir", "."));
    }
}
