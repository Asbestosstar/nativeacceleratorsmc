package com.asbestosstar.nativeaccelerator.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Lightweight native cache-size discovery. OSHI is deliberately not required. */
public final class CpuCacheDetector {
    private static final CpuCacheInfo CURRENT = detect();
    private CpuCacheDetector() {}
    public static CpuCacheInfo current() { return CURRENT; }

    private static CpuCacheInfo detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            CpuCacheInfo value = mac();
            if (value != null) return value;
        } else if (os.contains("linux")) {
            CpuCacheInfo value = linux();
            if (value != null) return value;
        }
        return new CpuCacheInfo(0, 0, 0, 0, "unavailable");
    }

    private static CpuCacheInfo mac() {
        List<Long> v = numericLines(run(Duration.ofMillis(900), "sysctl", "-n",
                "hw.l1dcachesize", "hw.l2cachesize", "hw.l3cachesize", "hw.cachelinesize"));
        if (v.size() < 4) v = numericLines(run(Duration.ofMillis(900), "/usr/sbin/sysctl", "-n",
                "hw.l1dcachesize", "hw.l2cachesize", "hw.l3cachesize", "hw.cachelinesize"));
        if (v.size() < 4) return null;
        return new CpuCacheInfo(v.get(0), v.get(1), v.get(2), safeInt(v.get(3)), "macos-sysctl");
    }

    private static CpuCacheInfo linux() {
        Path root = Path.of("/sys/devices/system/cpu/cpu0/cache");
        if (!Files.isDirectory(root)) return null;
        long l1d = 0, l2 = 0, l3 = 0;
        int line = 0;
        try (DirectoryStream<Path> indices = Files.newDirectoryStream(root, "index*")) {
            for (Path index : indices) {
                int level = parseInt(read(index.resolve("level")));
                String type = read(index.resolve("type"));
                long size = parseSize(read(index.resolve("size")));
                int cacheLine = parseInt(read(index.resolve("coherency_line_size")));
                line = Math.max(line, cacheLine);
                if (level == 1 && "Data".equalsIgnoreCase(type)) l1d = Math.max(l1d, size);
                else if (level == 2 && ("Data".equalsIgnoreCase(type) || "Unified".equalsIgnoreCase(type))) l2 = Math.max(l2, size);
                else if (level == 3 && ("Data".equalsIgnoreCase(type) || "Unified".equalsIgnoreCase(type))) l3 = Math.max(l3, size);
            }
        } catch (IOException ignored) { return null; }
        return new CpuCacheInfo(l1d, l2, l3, line, "linux-sysfs");
    }

    static long parseSize(String text) {
        if (text == null || text.isBlank()) return 0L;
        String s = text.trim().toUpperCase(Locale.ROOT);
        long multiplier = 1L;
        if (s.endsWith("K")) { multiplier = 1024L; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("M")) { multiplier = 1024L * 1024L; s = s.substring(0, s.length() - 1); }
        try { return Math.multiplyExact(Long.parseLong(s.trim()), multiplier); }
        catch (Exception ignored) { return 0L; }
    }

    private static String read(Path path) {
        try { return Files.readString(path, StandardCharsets.US_ASCII).trim(); }
        catch (IOException ignored) { return null; }
    }
    private static int parseInt(String text) {
        try { return text == null ? 0 : Integer.parseInt(text.trim()); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private static int safeInt(long value) { return (int)Math.max(0L, Math.min(Integer.MAX_VALUE, value)); }

    private static String run(Duration timeout, String... command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!p.waitFor(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return null; }
            if (p.exitValue() != 0) return null;
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        } finally { if (p != null && p.isAlive()) p.destroyForcibly(); }
    }
    private static List<Long> numericLines(String text) {
        ArrayList<Long> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\\R")) {
            try { out.add(Long.parseLong(line.trim())); } catch (NumberFormatException ignored) {}
        }
        return out;
    }
}

