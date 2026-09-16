package com.asbestosstar.nativeaccelerator.platform;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight host-native CPU topology discovery without OSHI.
 *
 * <p>Linux uses sysfs directly; macOS uses the native sysctl utility; Solaris/illumos uses kstat's cpu_info
 * topology (important for SPARC strands versus cores); Windows uses the OS CIM provider.  None of these paths
 * adds an always-resident hardware-monitoring dependency.  Failure is deliberately harmless: the detector
 * falls back to the JVM-visible processor count.</p>
 */
public final class CpuTopologyDetector {
    private static final Platform PLATFORM = Platform.current();
    private static final int JVM_CPUS = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final CpuTopology CURRENT = detect();

    private CpuTopologyDetector() {}

    public static CpuTopology current() {
        return CURRENT;
    }

    private static CpuTopology detect() {
        CpuTopology detected = switch (PLATFORM.os()) {
            case "macos" -> detectMacOs();
            case "linux" -> detectLinux();
            case "solaris" -> detectSolaris();
            case "windows" -> detectWindows();
            default -> null;
        };
        if (detected == null) detected = fallback();
        return applyOverrides(detected);
    }

    private static CpuTopology detectMacOs() {
        // Topology keys are stable on Intel and Apple Silicon. Frequency keys are optional.
        String out = run(Duration.ofMillis(1200), "sysctl", "-n",
                "hw.packages", "hw.physicalcpu", "hw.logicalcpu");
        List<Long> values = numericLines(out);
        if (values.size() < 3) {
            out = run(Duration.ofMillis(1200), "/usr/sbin/sysctl", "-n",
                    "hw.packages", "hw.physicalcpu", "hw.logicalcpu");
            values = numericLines(out);
        }
        if (values.size() < 3) return null;
        int packages = safeInt(values.get(0));
        int physical = safeInt(values.get(1));
        int logical = safeInt(values.get(2));

        int nominalMHz = 0;
        int maxMHz = 0;
        List<Long> nominal = numericLines(run(Duration.ofMillis(700), "sysctl", "-n", "hw.cpufrequency"));
        if (!nominal.isEmpty()) nominalMHz = hzToMHz(nominal.get(0));
        List<Long> maximum = numericLines(run(Duration.ofMillis(700), "sysctl", "-n", "hw.cpufrequency_max"));
        if (!maximum.isEmpty()) maxMHz = hzToMHz(maximum.get(0));
        return topology(packages, physical, logical, nominalMHz, maxMHz, "macos-sysctl");
    }

    private static CpuTopology detectLinux() {
        Path root = Path.of("/sys/devices/system/cpu");
        if (!Files.isDirectory(root)) return null;
        Set<String> packages = new HashSet<>();
        Set<String> cores = new HashSet<>();
        int logical = 0;
        long nominalKHz = 0L;
        long maxKHz = 0L;
        try (DirectoryStream<Path> cpus = Files.newDirectoryStream(root, "cpu[0-9]*")) {
            for (Path cpu : cpus) {
                if (!isOnlineLinuxCpu(cpu)) continue;
                logical++;
                String packageId = readTrimmed(cpu.resolve("topology/physical_package_id"));
                String coreId = readTrimmed(cpu.resolve("topology/core_id"));
                if (packageId != null) packages.add(packageId);
                if (coreId != null) cores.add((packageId == null ? "?" : packageId) + ':' + coreId);
                nominalKHz = Math.max(nominalKHz, readLong(cpu.resolve("cpufreq/base_frequency")));
                maxKHz = Math.max(maxKHz, readLong(cpu.resolve("cpufreq/cpuinfo_max_freq")));
                maxKHz = Math.max(maxKHz, readLong(cpu.resolve("cpufreq/scaling_max_freq")));
            }
        } catch (IOException ignored) {
            return null;
        }
        if (logical == 0) return null;
        int physical = cores.isEmpty() ? Math.min(logical, JVM_CPUS) : cores.size();
        int packageCount = packages.isEmpty() ? 1 : packages.size();
        int nominalMHz = nominalKHz > 0 ? safeInt((nominalKHz + 500L) / 1000L) : 0;
        int maxMHz = maxKHz > 0 ? safeInt((maxKHz + 500L) / 1000L) : linuxCpuInfoMHz();
        return topology(packageCount, physical, logical, nominalMHz, maxMHz, "linux-sysfs");
    }

    private static CpuTopology detectSolaris() {
        String output = run(Duration.ofMillis(1800), "kstat", "-p", "cpu_info");
        if (output == null || output.isBlank()) output = run(Duration.ofMillis(1800), "/usr/bin/kstat", "-p", "cpu_info");
        if (output == null || output.isBlank()) return null;

        // kstat lines look like cpu_info:<instance>:<name>:<field>\t<value>.
        Map<String, Map<String, String>> byInstance = new HashMap<>();
        for (String line : output.split("\\R")) {
            int tab = line.indexOf('\t');
            if (tab < 0) tab = line.indexOf(' ');
            if (tab <= 0) continue;
            String key = line.substring(0, tab).trim();
            String value = line.substring(tab + 1).trim();
            String[] parts = key.split(":", 4);
            if (parts.length != 4 || !parts[0].equals("cpu_info")) continue;
            byInstance.computeIfAbsent(parts[1], ignored -> new HashMap<>()).put(parts[3], value);
        }
        if (byInstance.isEmpty()) return null;

        Set<String> chips = new HashSet<>();
        Set<String> cores = new HashSet<>();
        int mhz = 0;
        for (Map<String, String> cpu : byInstance.values()) {
            String chip = first(cpu, "chip_id", "pkg_id", "package_id");
            String core = first(cpu, "core_id", "core-id");
            if (chip != null) chips.add(chip);
            if (core != null) cores.add((chip == null ? "?" : chip) + ':' + core);
            String clockMHz = first(cpu, "clock_MHz", "clock_mhz");
            if (clockMHz != null) {
                mhz = Math.max(mhz, parsePositiveInt(clockMHz, false));
            } else {
                String clockHz = first(cpu, "current_clock_Hz");
                if (clockHz != null) mhz = Math.max(mhz, parsePositiveInt(clockHz, true));
            }
        }
        int logical = byInstance.size(); // SPARC cpu_info instances are hardware strands/virtual CPUs.
        int physical = cores.isEmpty() ? Math.min(logical, JVM_CPUS) : cores.size();
        int packageCount = chips.isEmpty() ? 1 : chips.size();
        return topology(packageCount, physical, logical, mhz, mhz, "solaris-kstat");
    }

    private static CpuTopology detectWindows() {
        String script = "$p=@(Get-CimInstance Win32_Processor); "
                + "($p.Count); (($p|Measure-Object NumberOfCores -Sum).Sum); "
                + "(($p|Measure-Object NumberOfLogicalProcessors -Sum).Sum); "
                + "(($p|Measure-Object MaxClockSpeed -Maximum).Maximum)";
        String output = run(Duration.ofSeconds(2), "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
        List<Long> values = numericLines(output);
        if (values.size() < 4) return null;
        int clock = safeInt(values.get(3));
        return topology(safeInt(values.get(0)), safeInt(values.get(1)), safeInt(values.get(2)),
                clock, clock, "windows-cim");
    }

    private static CpuTopology topology(int packages, int physical, int logical, int nominalMHz, int maxMHz, String source) {
        return new CpuTopology(packages, physical, logical, JVM_CPUS, nominalMHz, maxMHz, PLATFORM.arch(), source);
    }

    private static CpuTopology fallback() {
        return new CpuTopology(1, JVM_CPUS, JVM_CPUS, JVM_CPUS, 0, 0, PLATFORM.arch(), "jvm-fallback");
    }

    private static CpuTopology applyOverrides(CpuTopology value) {
        int packages = NativeAcceleratorConfig.intValue("cpu.packages", value.packages(), 1);
        int physical = NativeAcceleratorConfig.intValue("cpu.physicalCores", value.physicalCores(), 1);
        int logical = NativeAcceleratorConfig.intValue("cpu.logicalProcessors", value.logicalProcessors(), 1);
        int nominalMHz = NativeAcceleratorConfig.intValue("cpu.nominalClockMHz", value.nominalClockMHz(), 0);
        int maxMHz = NativeAcceleratorConfig.intValue("cpu.maxClockMHz", value.maxClockMHz(), 0);
        if (packages == value.packages() && physical == value.physicalCores()
                && logical == value.logicalProcessors() && nominalMHz == value.nominalClockMHz()
                && maxMHz == value.maxClockMHz()) return value;
        return new CpuTopology(packages, physical, logical, value.jvmAvailableProcessors(), nominalMHz, maxMHz,
                value.architecture(), value.source() + "+override");
    }

    static CpuTopology parseSolarisKstatForTest(String output, int jvmProcessors, String architecture) {
        Map<String, Map<String, String>> byInstance = new HashMap<>();
        for (String line : output.split("\\R")) {
            String[] columns = line.trim().split("\\s+", 2);
            if (columns.length != 2) continue;
            String[] parts = columns[0].split(":", 4);
            if (parts.length != 4 || !parts[0].equals("cpu_info")) continue;
            byInstance.computeIfAbsent(parts[1], ignored -> new HashMap<>()).put(parts[3], columns[1].trim());
        }
        Set<String> chips = new HashSet<>();
        Set<String> cores = new HashSet<>();
        int mhz = 0;
        for (Map<String, String> cpu : byInstance.values()) {
            String chip = first(cpu, "chip_id", "pkg_id", "package_id");
            String core = first(cpu, "core_id", "core-id");
            if (chip != null) chips.add(chip);
            if (core != null) cores.add((chip == null ? "?" : chip) + ':' + core);
            String clock = first(cpu, "clock_MHz", "clock_mhz");
            if (clock != null) mhz = Math.max(mhz, parseInteger(clock));
        }
        return new CpuTopology(Math.max(1, chips.size()), Math.max(1, cores.size()), Math.max(1, byInstance.size()),
                Math.max(1, jvmProcessors), mhz, mhz, architecture, "solaris-kstat-test");
    }

    private static boolean isOnlineLinuxCpu(Path cpu) {
        Path online = cpu.resolve("online");
        if (!Files.isRegularFile(online)) return true; // cpu0 commonly has no online file.
        return "1".equals(readTrimmed(online));
    }

    private static String readTrimmed(Path path) {
        try {
            return Files.readString(path, StandardCharsets.US_ASCII).trim();
        } catch (IOException ignored) {
            return null;
        }
    }

    private static long readLong(Path path) {
        String value = readTrimmed(path);
        if (value == null) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static int linuxCpuInfoMHz() {
        Path cpuInfo = Path.of("/proc/cpuinfo");
        if (!Files.isRegularFile(cpuInfo)) return 0;
        Pattern p = Pattern.compile("(?im)^(?:cpu MHz|clock)\\s*:\\s*([0-9.]+)");
        try {
            Matcher m = p.matcher(Files.readString(cpuInfo, StandardCharsets.US_ASCII));
            double maximum = 0.0;
            while (m.find()) maximum = Math.max(maximum, Double.parseDouble(m.group(1)));
            return maximum > 0.0 ? (int) Math.round(maximum) : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String run(Duration timeout, String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) return null;
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static List<Long> numericLines(String text) {
        ArrayList<Long> values = new ArrayList<>();
        if (text == null) return values;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            try {
                values.add(Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {
                // Ignore diagnostics from optional/missing sysctl keys.
            }
        }
        return values;
    }

    private static int hzToMHz(long hz) {
        if (hz <= 0L) return 0;
        return safeInt((hz + 500_000L) / 1_000_000L);
    }

    private static int safeInt(long value) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }

    private static String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static int parsePositiveInt(String value, boolean hz) {
        if (value == null) return 0;
        long parsed;
        try {
            String token = value.trim().split("[ ,]", 2)[0];
            parsed = Long.parseLong(token);
        } catch (NumberFormatException ignored) {
            return 0;
        }
        return hz ? hzToMHz(parsed) : safeInt(parsed);
    }

    private static int parseInteger(String value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value.trim().split("\\s+", 2)[0]);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
