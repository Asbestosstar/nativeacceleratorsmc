package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderTechnology;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackCatalog;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ShaderpackCatalogTest {
    public static void main(String[] args) throws Exception {
        Path game = Files.createTempDirectory("nativeaccelerator-shaderpacks-");
        try {
            ShaderpackCatalog catalog = new ShaderpackCatalog(game);
            if (!catalog.scan().isEmpty()) throw new AssertionError("new catalog was not empty");
            Files.createDirectories(game.resolve("shaderpacks/FolderPack/shaders"));
            Files.writeString(game.resolve("shaderpacks/FolderPack/shaders/test.vert"), "#version 460\n");
            Files.writeString(game.resolve("shaderpacks/FolderPack/shaders/test.isl"), "FB = color;");
            Path zip = game.resolve("shaderpacks/ZipPack.zip");
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
                output.putNextEntry(new ZipEntry("root/shaders/test.frag"));
                output.write("#version 460\n".getBytes());
                output.closeEntry();
            }
            var packs = catalog.scan();
            if (packs.size() != 2 || !"FolderPack".equals(packs.get(0).id())) {
                throw new AssertionError("packs were not deterministically sorted: " + packs);
            }
            var folder = packs.get(0);
            if (!folder.technologies().contains(ShaderTechnology.GLSL)
                    || !folder.technologies().contains(ShaderTechnology.ISL)
                    || folder.status() != ShaderpackStatus.INCOMPLETE
                    || folder.conventionalProfile().format() != com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackFormat.UNKNOWN) {
                throw new AssertionError("technology/status classification was not conservative: " + folder);
            }
            catalog.select("FolderPack");
            if (!"FolderPack".equals(catalog.selectedId())) throw new AssertionError("selection did not persist");
            if (catalog.isSafeId("../outside") || catalog.isSafeId("a/b")) {
                throw new AssertionError("unsafe selection id accepted");
            }
            boolean rejected = false;
            try { catalog.select("../outside"); } catch (IllegalArgumentException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("unsafe selection was not rejected");
            catalog.select("");
            if (!catalog.selectedId().isEmpty()) throw new AssertionError("vanilla selection did not clear");
            System.out.println("ShaderpackCatalogTest: PASS (folder/zip discovery, classification, safe selection)");
        } finally {
            try (var walk = Files.walk(game)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} });
            }
        }
    }
}
