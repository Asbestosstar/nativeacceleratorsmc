package com.asbestosstar.nativeaccelerator.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Pure-JDK regression test: indexed prefix results must equal vanilla filtering in original ZIP order. */
public final class ZipResourceIndexTest {
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("nativeaccelerator-zip-index", ".zip");
        List<String> names = List.of(
                "assets/zeta/models/z.json",
                "assets/minecraft/textures/a.png",
                "assets/minecraft/models/block/b.json",
                "data/minecraft/tags/a.json",
                "assets/minecraft/models/item/a.json",
                "assets/minecraft/models/block/a.json",
                "assets/other/models/a.json",
                "assets/minecraft/lang/en_us.json");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("assets/empty_namespace/"));
            out.closeEntry();
            for (String name : names) {
                out.putNextEntry(new ZipEntry(name));
                out.write(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ArrayList<String> centralOrder = new ArrayList<>();
            centralOrder.add("assets/empty_namespace/");
            centralOrder.addAll(names);
            assertPrefix(zip, "assets/minecraft/models/", centralOrder);
            assertPrefix(zip, "assets/minecraft/", centralOrder);
            assertPrefix(zip, "assets/", centralOrder);
            assertPrefix(zip, "data/", centralOrder);
            assertPrefix(zip, "missing/", centralOrder);
        } finally {
            Files.deleteIfExists(file);
        }
        System.out.println("ZipResourceIndexTest: OK");
    }

    private static void assertPrefix(ZipFile zip, String prefix, List<String> original) {
        List<String> expected = original.stream().filter(name -> name.startsWith(prefix)).toList();
        List<String> actual = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = ZipResourceIndex.entries(zip, prefix);
        while (entries.hasMoreElements()) actual.add(entries.nextElement().getName());
        if (!actual.equals(expected)) throw new AssertionError(prefix + " expected=" + expected + " actual=" + actual);
    }
}
