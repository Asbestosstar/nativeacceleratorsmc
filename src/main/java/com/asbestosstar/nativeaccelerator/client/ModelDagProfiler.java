package com.asbestosstar.nativeaccelerator.client;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wall-clock profiler for the asynchronous ModelManager dependency graph.
 *
 * <p>This is intentionally separate from Minecraft's ProfiledReloadInstance counters. Those counters add
 * worker task CPU time and may exceed elapsed wall time when tasks overlap. This profiler records elapsed
 * spans and completion offsets from the start of ModelManager.reload(), which makes the actual straggler
 * visible.</p>
 */
public final class ModelDagProfiler {
    private static final boolean ENABLED = NativeAcceleratorConfig.booleanValue("model.dagProfiler", false);
    private static final ConcurrentHashMap<String, Stage> STAGES = new ConcurrentHashMap<>();
    private static final AtomicLong COMPLETION_SEQUENCE = new AtomicLong();
    private static final List<String> CRITICAL_PREFIXES = List.of(
            "raw-models", "blockstates", "item-definitions", "model-discovery", "model-groups",
            "entity-model-set", "built-in-block-models", "atlas.minecraft.blocks", "atlas.minecraft.items",
            "model-bakery.bake", "loaded-block-models.bake", "blockstate-dispatch.final", "model-load.final");
    private static volatile long reloadStartedNanos;

    private ModelDagProfiler() {}

    public static boolean enabled() { return ENABLED; }

    public static void beginReload() {
        if (!ENABLED) return;
        STAGES.clear();
        COMPLETION_SEQUENCE.set(0L);
        reloadStartedNanos = System.nanoTime();
    }

    public static long begin() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void end(String name, long startedNanos) {
        if (!ENABLED || startedNanos == 0L) return;
        complete(name, startedNanos, System.nanoTime());
    }

    public static <T> CompletionStage<T> track(String name, CompletionStage<T> stage, long startedNanos) {
        if (!ENABLED || stage == null) return stage;
        final long started = startedNanos != 0L ? startedNanos : System.nanoTime();
        stage.whenComplete((value, failure) -> complete(name, started, System.nanoTime()));
        return stage;
    }

    public static <T> CompletionStage<T> track(String name, CompletionStage<T> stage) {
        return track(name, stage, System.nanoTime());
    }

    public static void markCompletion(String name) {
        if (!ENABLED) return;
        long now = System.nanoTime();
        complete(name, now, now);
    }

    private static void complete(String name, long started, long ended) {
        if (name == null || name.isBlank()) return;
        long reloadStart = reloadStartedNanos;
        Stage value = new Stage(name, Math.max(0L, ended - started),
                reloadStart == 0L ? 0L : Math.max(0L, ended - reloadStart),
                COMPLETION_SEQUENCE.incrementAndGet());
        STAGES.merge(name, value, Stage::later);
    }

    public static String report() {
        if (!ENABLED) return "";
        List<Stage> rows = new ArrayList<>(STAGES.values());
        rows.sort(Comparator.comparingLong(Stage::completedOffsetNanos));
        StringBuilder out = new StringBuilder(2048);
        out.append("[Native Accelerator] ModelManager DAG wall-clock profiler\n");
        Stage straggler = null;
        for (Stage row : rows) {
            if (row.name().equals("model-manager.total")) continue;
            if (isCriticalDependency(row.name())
                    && (straggler == null || row.completedOffsetNanos() > straggler.completedOffsetNanos())) {
                straggler = row;
            }
            out.append(String.format(Locale.ROOT,
                    "  %-38s wall=%8.2f ms  completed=+%8.2f ms  order=%4d%n",
                    row.name(), row.durationNanos() / 1_000_000.0,
                    row.completedOffsetNanos() / 1_000_000.0, row.completionOrder()));
        }
        Stage total = STAGES.get("model-manager.total");
        if (total != null) {
            out.append(String.format(Locale.ROOT, "  %-38s wall=%8.2f ms%n",
                    "MODEL MANAGER TOTAL", total.durationNanos() / 1_000_000.0));
        }
        if (straggler != null) {
            out.append("  latest tracked ModelManager dependency: ").append(straggler.name()).append('\n');
        }
        return out.toString();
    }

    private static boolean isCriticalDependency(String name) {
        for (String prefix : CRITICAL_PREFIXES) if (name.startsWith(prefix)) return true;
        return false;
    }

    private record Stage(String name, long durationNanos, long completedOffsetNanos, long completionOrder) {
        static Stage later(Stage a, Stage b) {
            return a.completedOffsetNanos >= b.completedOffsetNanos ? a : b;
        }
    }
}

