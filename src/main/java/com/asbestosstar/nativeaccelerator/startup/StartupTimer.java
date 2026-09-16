package com.asbestosstar.nativeaccelerator.startup;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loader-neutral startup stopwatch behind the startup-timing Mixins.
 *
 * <p>This class deliberately references no Minecraft, Mixin, or loader type. The Mixins only call
 * {@link #begin(String)}, {@link #end(String)}, {@link #mark(String)} and {@link #finish(String)}; all
 * formatting, ranking, file output and baseline comparison lives here, so the logic is testable without
 * launching the game (see {@code StartupTimerTest} and {@code run-startup-timer-test.sh}).</p>
 *
 * <h2>What is measured</h2>
 * <ul>
 *   <li><b>stages</b> ({@link #begin}/{@link #end}) - a named interval such as
 *       {@code bootstrap.fire-block}. Ranked by duration so the slowest parts of startup are obvious.</li>
 *   <li><b>lifetime stages</b> ({@link #beginLifetime}/{@link #endLifetime}) - intervals that only end when
 *       the process exits (the client tick loop, the server run loop). Recorded and printed, but excluded
 *       from the ranking and from percentages, because "the whole session" would swamp every real stage.</li>
 *   <li><b>milestones</b> ({@link #mark}) - an instant such as {@code client.title-screen} or
 *       {@code server.ready}, printed as milliseconds since process start. That is the number which answers
 *       "how long does the game take to load?".</li>
 * </ul>
 *
 * <p>Times are relative to the real process start ({@link ProcessHandle#info()}), not to class
 * initialization, so the report includes JVM start-up and Mixin loading before our first probe runs.</p>
 *
 * <p>The first occurrence of a stage wins, which is what makes runs comparable: a later resource reload
 * must not overwrite the initial startup reload.</p>
 *
 * <h2>Properties</h2>
 * <ul>
 *   <li>{@code -Dnativeaccelerator.startup.timing=false} - disable all recording and output.</li>
 *   <li>{@code -Dnativeaccelerator.startup.report=<path>} - TSV report location (default
 *       {@value #DEFAULT_REPORT_FILE}; {@value #DISABLED_VALUE} skips the file).</li>
 *   <li>{@code -Dnativeaccelerator.startup.baseline=<path>} - baseline to compare against (default
 *       {@value #DEFAULT_BASELINE_FILE}).</li>
 *   <li>{@code -Dnativeaccelerator.startup.record=true} - also write this run to the baseline path, so the
 *       next run reports it as the reference.</li>
 * </ul>
 *
 * <h2>Measuring an improvement</h2>
 * <ol>
 *   <li>Run once with {@code -Dnativeaccelerator.startup.record=true} to snapshot the baseline.</li>
 *   <li>Change the code and run again.</li>
 *   <li>Read the {@code baseline} block: each stage prints {@code baseline -> current}, the delta in
 *       milliseconds and percent, and {@code FASTER}/{@code SLOWER}. {@code OVERALL} is time-to-startup,
 *       the single number to quote.</li>
 * </ol>
 *
 * <p>Every method is safe to call before Minecraft is initialized and never throws: reporting must not be
 * able to break the game it measures.</p>
 */
public final class StartupTimer {

    /** Master switch for all startup timing. */
    public static final String ENABLED_PROPERTY = "nativeaccelerator.startup.timing";
    /** TSV report destination, or {@link #DISABLED_VALUE}. */
    public static final String REPORT_PROPERTY = "nativeaccelerator.startup.report";
    /** Baseline TSV read for the comparison block. */
    public static final String BASELINE_PROPERTY = "nativeaccelerator.startup.baseline";
    /** {@code true} writes the current run to the baseline path when the report is finished. */
    public static final String RECORD_PROPERTY = "nativeaccelerator.startup.record";

    public static final String DEFAULT_REPORT_FILE = "logs/nativeaccelerator-startup-times.tsv";
    public static final String DEFAULT_BASELINE_FILE = "logs/nativeaccelerator-startup-baseline.tsv";
    /** Value of {@link #REPORT_PROPERTY} that suppresses the report file. */
    public static final String DISABLED_VALUE = "none";

    private static final long PROCESS_START_NANOS = System.nanoTime();
    private static final long PROCESS_START_EPOCH_MILLIS = readProcessStartEpochMillis();
    private static final int RANKING_LIMIT = 15;

    private static final Map<String, Stage> STAGES = new LinkedHashMap<>();
    private static final Set<String> WARNED = new HashSet<>();
    private static final AtomicBoolean MILESTONE_PRINTED = new AtomicBoolean();
    private static final AtomicBoolean SHUTDOWN_HOOK = new AtomicBoolean();

    private StartupTimer() {}

    /* ------------------------------------------------------------------ recording ---------------- */

    /** Whether recording and output are enabled ({@value #ENABLED_PROPERTY}, default true). */
    public static boolean enabled() {
        String value = com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.stringValue("startup.timing", "true");
        return !value.isBlank() && Boolean.parseBoolean(value.trim());
    }

    /** Start (or count a repeat of) a ranked stage. The first start is the one reported. */
    public static synchronized void begin(String stage) {
        recordStart(stage, true);
    }

    /** Start a stage that ends at process exit; recorded but excluded from ranking and percentages. */
    public static synchronized void beginLifetime(String stage) {
        recordStart(stage, false);
    }

    /** End a ranked stage. A no-op when the stage was never started or already ended. */
    public static synchronized void end(String stage) {
        recordEnd(stage);
    }

    /** End a lifetime stage (same bookkeeping as {@link #end(String)}). */
    public static synchronized void endLifetime(String stage) {
        recordEnd(stage);
    }

    /** Record a one-off instant, for example the first title screen or the server becoming ready. */
    public static synchronized void mark(String stage) {
        if (!accept(stage)) return;
        installShutdownHook();

        Stage existing = STAGES.get(stage);
        if (existing == null) {
            Stage created = new Stage(stage);
            created.milestone = true;
            created.markNanos = System.nanoTime();
            STAGES.put(stage, created);
        } else if (existing.markNanos == 0L) {
            existing.markNanos = System.nanoTime();
        }
    }

    private static void recordStart(String stage, boolean ranked) {
        if (!accept(stage)) return;
        installShutdownHook();

        Stage existing = STAGES.get(stage);
        if (existing == null) {
            Stage created = new Stage(stage);
            created.ranked = ranked;
            STAGES.put(stage, created);
        } else {
            existing.occurrences++;
        }
    }

    private static void recordEnd(String stage) {
        if (!accept(stage)) return;

        Stage existing = STAGES.get(stage);
        if (existing == null) {
            if (WARNED.add(stage)) {
                System.out.println("[Native Accelerator] startup timing: end without begin for " + stage);
            }
            return;
        }
        if (existing.endNanos == 0L) existing.endNanos = System.nanoTime();
    }

    private static boolean accept(String stage) {
        return enabled() && stage != null && !stage.isBlank();
    }

    /* ------------------------------------------------------------------ reporting ---------------- */

    /**
     * Print the report, write the report file, and (with {@link #RECORD_PROPERTY}) update the baseline.
     *
     * <p><b>Idempotent.</b> Only the first call in a process does the work; later calls return immediately.
     * This matters because a probe can sit on an instruction that legitimately repeats. The clearest case is
     * the server-ready milestone: {@code MinecraftServer#runServer()} executes {@code this.isReady = true;} at
     * the end of <em>every</em> tick, so a field-write injection there runs about twenty times a second. A
     * non-idempotent report would flood the log and rewrite the TSV on every tick. Keeping the run-once
     * policy here (rather than in the Mixin) lets the Mixins stay a thin seam that only calls
     * {@link StartupTimer}. {@link #resetForTests()} clears the flag so a harness can exercise this path.</p>
     *
     * <p>The shutdown hook writes a final report file regardless, and re-prints only when no milestone has
     * reported yet.</p>
     */
    public static synchronized void finish(String reason) {
        if (!enabled()) return;
        if (!MILESTONE_PRINTED.compareAndSet(false, true)) return;
        System.out.println(report());
        if (reason != null && !reason.isBlank()) {
            System.out.println("  (startup timing reported at: " + reason + ")");
        }
        writeReportFile(reason);
        if (Boolean.parseBoolean(com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.stringValue("startup.record", "false"))) {
            writeBaseline(baselinePath());
        }
    }

    /** The full report as text, including the baseline comparison when a baseline file is readable. */
    public static synchronized String report() {
        long markNanos = startupMarkNanos();
        String milestoneName = startupMilestoneName();

        StringBuilder out = new StringBuilder(2048);
        out.append("[Native Accelerator] startup timing").append('\n');
        out.append("  process start: ").append(Instant.ofEpochMilli(PROCESS_START_EPOCH_MILLIS)).append('\n');
        if (markNanos > 0L && milestoneName != null) {
            out.append("  time to ").append(milestoneName).append(": ")
                    .append(ms(markNanos - PROCESS_START_NANOS)).append(" ms\n");
        }

        List<Stage> finished = new ArrayList<>();
        for (Stage stage : STAGES.values()) {
            if (!stage.milestone && stage.startNanos != 0L && stage.endNanos != 0L) finished.add(stage);
        }

        long denominator = markNanos > 0L ? markNanos - PROCESS_START_NANOS : 0L;
        if (denominator <= 0L) {
            for (Stage stage : finished) {
                if (stage.ranked) denominator = Math.max(denominator, stage.endNanos - stage.startNanos);
            }
        }
        if (denominator <= 0L) denominator = 1L;

        out.append("  stages (start/duration in ms; % is share of ")
                .append(markNanos > 0L ? "time-to-startup" : "longest ranked stage").append("):\n");
        out.append(String.format(Locale.ROOT, "    %-34s %10s %10s %8s%n", "stage", "start", "dur", "%"));
        List<Stage> ranked = new ArrayList<>();
        List<Stage> lifetime = new ArrayList<>();
        for (Stage stage : finished) {
            (stage.ranked ? ranked : lifetime).add(stage);
        }
        for (Stage stage : ranked) {
            long duration = stage.endNanos - stage.startNanos;
            out.append(String.format(Locale.ROOT, "    %-34s %10s %10s %7.1f%%%n",
                    stage.name, ms(stage.startNanos - PROCESS_START_NANOS), ms(duration),
                    100.0 * duration / denominator));
        }

        if (!lifetime.isEmpty()) {
            out.append("  lifetime stages (end at process exit; excluded from the ranking):\n");
            for (Stage stage : lifetime) {
                out.append(String.format(Locale.ROOT, "    %-34s %10s ms%n",
                        stage.name, ms(stage.endNanos - stage.startNanos)));
            }
        }

        ranked.sort(Comparator.comparingLong((Stage stage) -> stage.endNanos - stage.startNanos).reversed());
        if (!ranked.isEmpty()) {
            out.append("  slowest stages:\n");
            int shown = 0;
            for (Stage stage : ranked) {
                if (shown++ >= RANKING_LIMIT) break;
                out.append(String.format(Locale.ROOT, "    %-34s %10s ms%n",
                        stage.name, ms(stage.endNanos - stage.startNanos)));
            }
        }

        boolean anyMilestone = false;
        for (Stage stage : STAGES.values()) {
            if (!stage.milestone || stage.markNanos == 0L) continue;
            anyMilestone = true;
            out.append(String.format(Locale.ROOT, "  milestone %-28s %10s ms since process start%n",
                    stage.name, ms(stage.markNanos - PROCESS_START_NANOS)));
        }
        if (!anyMilestone && finished.isEmpty()) {
            out.append("  no stages recorded (timing disabled, or no probe has run yet)\n");
        }

        appendBaselineComparison(out, finished, markNanos, milestoneName);
        return stripTrailingNewlines(out.toString());
    }

    private static void appendBaselineComparison(StringBuilder out, List<Stage> finished,
                                                 long markNanos, String milestoneName) {
        Map<String, Double> baseline = readBaseline(baselinePath());
        if (baseline.isEmpty() || finished.isEmpty()) return;

        out.append("  baseline ").append(baselinePath()).append(" (baseline -> current):\n");
        for (Stage stage : finished) {
            Double before = baseline.get(stage.name);
            if (before == null) continue;
            appendComparison(out, stage.name, before, msValue(stage.endNanos - stage.startNanos));
        }
        if (markNanos > 0L && milestoneName != null && baseline.containsKey(milestoneName)) {
            appendComparison(out, "OVERALL " + milestoneName, baseline.get(milestoneName),
                    msValue(markNanos - PROCESS_START_NANOS));
        }
    }

    private static void appendComparison(StringBuilder out, String label, double before, double now) {
        double delta = now - before;
        out.append(String.format(Locale.ROOT, "    %-34s %10.1f -> %10.1f  %+9.1f ms (%+6.1f%%) %s%n",
                label, before, now, delta, before <= 0.0 ? 0.0 : 100.0 * delta / before, verdict(delta)));
    }

    private static void installShutdownHook() {
        if (!SHUTDOWN_HOOK.compareAndSet(false, true)) return;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    // A milestone report already showed the tester everything; at shutdown only the file
                    // changes (it gains the final lifetime stages). Otherwise print the late report too.
                    if (!MILESTONE_PRINTED.get()) System.out.println(report());
                    writeReportFile("shutdown");
                } catch (Throwable ignored) {
                    // Never let reporting interfere with shutdown.
                }
            }, "nativeaccelerator-startup-report"));
        } catch (Throwable ignored) {
            // A runtime that forbids shutdown hooks just loses the automatic report; probes can still call
            // finish(...) explicitly.
        }
    }

    private static void writeReportFile(String reason) {
        String configured = System.getProperty(REPORT_PROPERTY, DEFAULT_REPORT_FILE).trim();
        if (configured.isEmpty() || DISABLED_VALUE.equalsIgnoreCase(configured)) return;
        try {
            Path path = Paths.get(configured);
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);

            StringBuilder sb = new StringBuilder(1024);
            sb.append("# Native Accelerator startup times v1").append('\n');
            sb.append("# reason=").append(reason == null ? "" : reason)
                    .append(" processStart=").append(Instant.ofEpochMilli(PROCESS_START_EPOCH_MILLIS))
                    .append('\n');
            sb.append("# stage\tkind\tstartMs\tdurationMs\tsinceProcessStartMs").append('\n');

            for (Stage stage : STAGES.values()) {
                String kind = stage.milestone ? "milestone" : (stage.ranked ? "stage" : "lifetime");
                sb.append(stage.name).append('\t').append(kind).append('\t')
                        .append(stage.startNanos == 0L ? "" : ms(stage.startNanos - PROCESS_START_NANOS)).append('\t')
                        .append(stage.endNanos == 0L ? "" : ms(stage.endNanos - stage.startNanos)).append('\t')
                        .append(stage.markNanos == 0L ? "" : ms(stage.markNanos - PROCESS_START_NANOS))
                        .append('\n');
            }

            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("[Native Accelerator] startup times written to " + path.toAbsolutePath());
        } catch (Throwable ignored) {
            // Best-effort: a read-only working directory must not fail startup.
        }
    }

    /* ------------------------------------------------------------------ baselines ---------------- */

    /**
     * One number per stage: the duration for stages, and milliseconds since process start for milestones.
     * That single metric per name is what makes the baseline a plain two-column TSV.
     */
    private static Map<String, Double> primaryMetrics() {
        Map<String, Double> metrics = new LinkedHashMap<>();
        for (Stage stage : STAGES.values()) {
            if (stage.milestone) {
                if (stage.markNanos != 0L) metrics.put(stage.name, msValue(stage.markNanos - PROCESS_START_NANOS));
            } else if (stage.startNanos != 0L && stage.endNanos != 0L) {
                metrics.put(stage.name, msValue(stage.endNanos - stage.startNanos));
            }
        }
        return metrics;
    }

    private static Path baselinePath() {
        String configured = System.getProperty(BASELINE_PROPERTY, "").trim();
        return configured.isEmpty() ? Paths.get(DEFAULT_BASELINE_FILE) : Paths.get(configured);
    }

    private static void writeBaseline(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);

            StringBuilder sb = new StringBuilder(512);
            sb.append("# nativeaccelerator startup baseline v1").append('\n');
            for (Map.Entry<String, Double> entry : primaryMetrics().entrySet()) {
                sb.append(entry.getKey()).append('\t').append(fmt(entry.getValue())).append('\n');
            }
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("[Native Accelerator] startup baseline written to " + path.toAbsolutePath());
        } catch (Throwable ignored) {
            // Best-effort, same as the report.
        }
    }

    private static Map<String, Double> readBaseline(Path path) {
        Map<String, Double> values = new LinkedHashMap<>();
        try {
            if (!Files.isReadable(path)) return values;
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int tab = trimmed.indexOf('\t');
                if (tab <= 0 || tab + 1 >= trimmed.length()) continue;
                values.put(trimmed.substring(0, tab), Double.parseDouble(trimmed.substring(tab + 1).trim()));
            }
        } catch (Throwable ignored) {
            // A malformed baseline degrades to "no comparison", never to a failed startup.
            values.clear();
        }
        return values;
    }

    /* ------------------------------------------------------------------ helpers ------------------ */

    /** Milliseconds from process start to a milestone, or {@code -1} when it was not reached. */
    public static synchronized long millisSinceProcessStart(String milestone) {
        Stage existing = milestone == null ? null : STAGES.get(milestone);
        if (existing == null || existing.markNanos == 0L) return -1L;
        return (existing.markNanos - PROCESS_START_NANOS) / 1_000_000L;
    }

    /** Duration of a finished stage in milliseconds, or {@code -1} when absent or still running. */
    public static synchronized long elapsedMillis(String stage) {
        Stage existing = stage == null ? null : STAGES.get(stage);
        if (existing == null || existing.startNanos == 0L || existing.endNanos == 0L) return -1L;
        return (existing.endNanos - existing.startNanos) / 1_000_000L;
    }

    /**
     * The startup milestone that answers "how long did the game take to load?": the first title screen on
     * a client, or the server becoming ready on a dedicated server.
     */
    private static String startupMilestoneName() {
        if (hasMilestone(StartupStages.CLIENT_TITLE_SCREEN)) return StartupStages.CLIENT_TITLE_SCREEN;
        if (hasMilestone(StartupStages.SERVER_READY)) return StartupStages.SERVER_READY;
        return null;
    }

    private static boolean hasMilestone(String name) {
        Stage stage = STAGES.get(name);
        return stage != null && stage.markNanos != 0L;
    }

    /** Earliest recorded startup milestone in nanoTime terms, or {@code 0} when none was reached yet. */
    private static long startupMarkNanos() {
        long earliest = 0L;
        for (String name : new String[] {StartupStages.CLIENT_TITLE_SCREEN, StartupStages.SERVER_READY}) {
            Stage stage = STAGES.get(name);
            if (stage == null || stage.markNanos == 0L) continue;
            if (earliest == 0L || stage.markNanos < earliest) earliest = stage.markNanos;
        }
        return earliest;
    }

    private static long readProcessStartEpochMillis() {
        try {
            Optional<Instant> start = ProcessHandle.current().info().startInstant();
            if (start.isPresent()) return start.get().toEpochMilli();
        } catch (Throwable ignored) {
            // ProcessHandle is optional on some restricted runtimes. A slightly late "process start" only
            // makes the reported elapsed times conservative; it cannot break the stage measurements.
        }
        return System.currentTimeMillis();
    }

    /** Reset all recorded state. Only for the test harness; the game never calls this. */
    public static synchronized void resetForTests() {
        STAGES.clear();
        WARNED.clear();
        MILESTONE_PRINTED.set(false);
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    /** The same conversion as {@link #ms(long)} but as a number, for baseline arithmetic. */
    private static double msValue(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static String fmt(double millis) {
        return String.format(Locale.ROOT, "%.1f", millis);
    }

    private static String verdict(double delta) {
        if (delta <= -1.0) return "FASTER";
        if (delta >= 1.0) return "SLOWER";
        return "unchanged";
    }

    private static String stripTrailingNewlines(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '\n' || value.charAt(end - 1) == '\r')) end--;
        return value.substring(0, end);
    }

    /** One measured stage. Guarded by the {@code StartupTimer} monitor, so no field needs volatile. */
    private static final class Stage {
        final String name;
        final long startNanos;
        long endNanos;
        long markNanos;
        int occurrences = 1;
        boolean ranked = true;
        boolean milestone;

        Stage(String name) {
            this.name = name;
            this.startNanos = System.nanoTime();
        }
    }
}
