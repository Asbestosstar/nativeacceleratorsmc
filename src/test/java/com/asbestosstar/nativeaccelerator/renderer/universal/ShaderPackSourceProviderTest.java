package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ShaderPackSourceProviderTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("shader-pack-");
        try {
            Files.createDirectories(root.resolve("inc"));
            Files.writeString(root.resolve("main.glsl"), "#version 450\n#include \"inc/common.glsl\"\nvoid main() {}\n");
            Files.writeString(root.resolve("inc/common.glsl"), "const int VALUE = 7;\n");
            try (ShaderPackSource source = ShaderPackSourceProvider.open(root)) {
                require(source.list().equals(java.util.List.of("inc/common.glsl", "main.glsl")));
                require(source.read("inc\\common.glsl").isPresent());
                require(source.read("../outside").isEmpty());
                require(source.contentHash().equals(source.contentHash()));
                var resolver = new ShaderPackSourceResolver(source);
                require(resolver.resolve("main.glsl").contains("VALUE = 7"));
            }
            Path zip = root.resolve("pack.jar");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new ZipEntry("z.glsl")); out.write("z".getBytes()); out.closeEntry();
                out.putNextEntry(new ZipEntry("a.glsl")); out.write("a".getBytes()); out.closeEntry();
            }
            try (ShaderPackSource source = ShaderPackSourceProvider.open(zip)) {
                require(source.list().equals(java.util.List.of("a.glsl", "z.glsl")));
                require(source.read("a.glsl").get()[0] == 'a');
            }
            System.out.println("ShaderPackSourceProviderTest: PASS");
        } finally {
            try (var paths = Files.walk(root)) { paths.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } }); }
        }
    }
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
}
