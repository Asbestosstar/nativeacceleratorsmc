package com.asbestosstar.nativeaccelerator.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Lazy explicit strand layout discovery. The normal topology count path stays cheap and unchanged. */
public final class CpuTopologyLayoutDetector {
    private static final class Holder { static final CpuTopologyLayout CURRENT = detect(); }
    private CpuTopologyLayoutDetector() {}

    public static CpuTopologyLayout current() { return Holder.CURRENT; }

    private static CpuTopologyLayout detect() {
        Platform p = Platform.current();
        CpuTopologyLayout layout = switch (p.os()) {
            case "solaris" -> detectSolaris();
            case "linux" -> detectLinux();
            default -> null;
        };
        if (layout != null && !layout.cores().isEmpty()) return layout;
        return synthetic(CpuTopologyDetector.current());
    }

    private static CpuTopologyLayout detectSolaris() {
        String output = run(Duration.ofMillis(1800), "kstat", "-p", "cpu_info");
        if (output == null || output.isBlank()) output = run(Duration.ofMillis(1800), "/usr/bin/kstat", "-p", "cpu_info");
        if (output == null || output.isBlank()) return null;

        int[] allowedArray = SolarisThreadAffinity.allowedProcessorIds();
        Set<Integer> allowed = new HashSet<>();
        for (int cpu : allowedArray) allowed.add(cpu);

        Map<Integer, Map<String, String>> byCpu = new HashMap<>();
        for (String line : output.split("\\R")) {
            int split = line.indexOf('\t');
            if (split < 0) split = line.indexOf(' ');
            if (split <= 0) continue;
            String[] parts = line.substring(0, split).trim().split(":", 4);
            if (parts.length != 4 || !"cpu_info".equals(parts[0])) continue;
            int cpu;
            try { cpu = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) { continue; }
            if (!allowed.isEmpty() && !allowed.contains(cpu)) continue;
            byCpu.computeIfAbsent(cpu, ignored -> new HashMap<>()).put(parts[3], line.substring(split + 1).trim());
        }
        Map<String, ArrayList<Integer>> grouped = new HashMap<>();
        Map<String, int[]> ids = new HashMap<>();
        for (Map.Entry<Integer, Map<String, String>> entry : byCpu.entrySet()) {
            int cpu = entry.getKey();
            int chip = parse(entry.getValue().get("chip_id"), 0);
            String coreText = entry.getValue().get("core_id");
            if (coreText == null) coreText = entry.getValue().get("core-id");
            int core = parse(coreText, cpu);
            String key = chip + ":" + core;
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(cpu);
            ids.put(key, new int[]{chip, core});
        }
        if (grouped.isEmpty()) return null;
        ArrayList<CpuCoreLayout> cores = new ArrayList<>();
        for (Map.Entry<String, ArrayList<Integer>> e : grouped.entrySet()) {
            int[] id = ids.get(e.getKey());
            int[] cpus = e.getValue().stream().sorted().mapToInt(Integer::intValue).toArray();
            cores.add(new CpuCoreLayout(id[0], id[1], cpus));
        }
        return new CpuTopologyLayout(cores, allowed.isEmpty() ? "solaris-kstat" : "solaris-kstat+pset", true);
    }

    private static CpuTopologyLayout detectLinux() {
        Path root = Path.of("/sys/devices/system/cpu");
        if (!Files.isDirectory(root)) return null;
        Map<String, ArrayList<Integer>> grouped = new HashMap<>();
        Map<String, int[]> ids = new HashMap<>();
        try (DirectoryStream<Path> cpus = Files.newDirectoryStream(root, "cpu[0-9]*")) {
            for (Path cpuPath : cpus) {
                String name = cpuPath.getFileName().toString();
                int cpu;
                try { cpu = Integer.parseInt(name.substring(3)); } catch (RuntimeException ignored) { continue; }
                Path online = cpuPath.resolve("online");
                if (Files.isRegularFile(online) && !"1".equals(read(online))) continue;
                int pkg = parse(read(cpuPath.resolve("topology/physical_package_id")), 0);
                int core = parse(read(cpuPath.resolve("topology/core_id")), cpu);
                String key = pkg + ":" + core;
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(cpu);
                ids.put(key, new int[]{pkg, core});
            }
        } catch (IOException ignored) { return null; }
        if (grouped.isEmpty()) return null;
        ArrayList<CpuCoreLayout> cores = new ArrayList<>();
        for (Map.Entry<String, ArrayList<Integer>> e : grouped.entrySet()) {
            int[] id = ids.get(e.getKey());
            cores.add(new CpuCoreLayout(id[0], id[1], e.getValue().stream().sorted().mapToInt(Integer::intValue).toArray()));
        }
        return new CpuTopologyLayout(cores, "linux-sysfs", true);
    }

    private static CpuTopologyLayout synthetic(CpuTopology t) {
        int physical = Math.max(1, t.effectivePhysicalCores());
        int visible = Math.max(1, t.jvmAvailableProcessors());
        int smt = Math.max(1, Math.min(t.threadsPerCore(), (visible + physical - 1) / physical));
        ArrayList<CpuCoreLayout> cores = new ArrayList<>();
        int cpu = 0;
        for (int core = 0; core < physical && cpu < visible; ++core) {
            int n = Math.min(smt, visible - cpu);
            int[] ids = new int[n];
            for (int s = 0; s < n; ++s) ids[s] = cpu++;
            cores.add(new CpuCoreLayout(0, core, ids));
        }
        return new CpuTopologyLayout(cores, "synthetic-" + t.source(), false);
    }

    private static String read(Path path) {
        try { return Files.readString(path, StandardCharsets.US_ASCII).trim(); } catch (IOException ignored) { return null; }
    }
    private static int parse(String text, int fallback) {
        try { return text == null ? fallback : Integer.parseInt(text.trim().split("\\s+", 2)[0]); }
        catch (RuntimeException ignored) { return fallback; }
    }
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
}
