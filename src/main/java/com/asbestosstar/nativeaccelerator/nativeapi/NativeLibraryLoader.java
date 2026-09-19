package com.asbestosstar.nativeaccelerator.nativeapi;

import com.asbestosstar.nativeaccelerator.platform.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Extracts the native library bundled inside the mod JAR.
 *
 * <p>Do not rely on only {@link Class#getResourceAsStream(String)} here. Loader
 * environments such as Fabric/Knot can load transformed classes while not
 * exposing every META-INF resource through that particular lookup route. The
 * native binary is therefore resolved through several equivalent, read-only
 * mechanisms before being declared absent.</p>
 */
public final class NativeLibraryLoader {
    private static final String MOD_ID = "nativeaccelerator";

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

            Path dir = Files.createTempDirectory("nativeaccelerator-");
            Path file = dir.resolve(fileName);
            Files.write(file, bytes);
            file.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            System.out.println("[Native Accelerator] Extracted native library " + relative + " -> " + file);
            return file;
        }

        throw new IOException("No bundled Native Accelerator library for " + platform.nativeId()
                + " (mapped name " + fileName + ", searched " + attempted + ")");
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
