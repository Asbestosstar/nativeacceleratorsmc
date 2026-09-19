package com.asbestosstar.nativeaccelerator.nativeapi;

import com.asbestosstar.nativeaccelerator.platform.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class NativeLibraryLoader {
    private NativeLibraryLoader() {}

    public static Path extractBundledLibrary(String baseName) throws IOException {
        Platform platform = Platform.current();
        String fileName = Platform.mappedLibraryName(baseName);

        for (String platformId : platform.nativeResourceIds()) {
            String resource = "/META-INF/native/" + platformId + "/" + fileName;
            try (InputStream in = NativeLibraryLoader.class.getResourceAsStream(resource)) {
                if (in == null) continue;

                Path dir = Files.createTempDirectory("nativeaccelerator-");
                Path file = dir.resolve(fileName);
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                file.toFile().deleteOnExit();
                dir.toFile().deleteOnExit();
                return file;
            }
        }

        throw new IOException("No bundled Native Accelerator library for " + platform.nativeId()
                + " (searched " + platform.nativeResourceIds() + ")");
    }
}
