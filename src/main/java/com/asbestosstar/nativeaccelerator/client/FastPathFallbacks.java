package com.asbestosstar.nativeaccelerator.client;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Rate-limited diagnostics and aggregate reason counters for decoder fallbacks. */
public final class FastPathFallbacks {
    private static final ConcurrentHashMap<String, AtomicInteger> LOGGED = new ConcurrentHashMap<>();
    private static final int LOG_LIMIT = 8;
    private static final boolean LOG_FALLBACKS = NativeAcceleratorConfig.booleanValue("model.logFallbacks", false);

    private FastPathFallbacks() {}

    public static void record(String family, Throwable failure, String resource) {
        String reason = classify(failure);
        ModelPipelineProfiler.addCount(family + ".fallback.reason." + reason, 1);
        if (!LOG_FALLBACKS) return;
        AtomicInteger count = LOGGED.computeIfAbsent(family, ignored -> new AtomicInteger());
        int n = count.incrementAndGet();
        if (n <= LOG_LIMIT) {
            System.out.printf(Locale.ROOT, "[Native Accelerator] %s fast-path fallback (%s) for %s: %s%n",
                    family, reason, resource, failure == null ? "unknown" : String.valueOf(failure.getMessage()));
        } else if (n == LOG_LIMIT + 1) {
            System.out.printf(Locale.ROOT, "[Native Accelerator] %s fallback logging suppressed after %d entries%n",
                    family, LOG_LIMIT);
        }
    }

    private static String classify(Throwable failure) {
        if (failure == null) return "unknown";
        if (failure instanceof FastClientItemDecoder.UnsupportedFastPathException unsupported) {
            return sanitize(unsupported.reason());
        }
        if (failure instanceof IOException) return "io";
        String simple = failure.getClass().getSimpleName();
        if (simple.contains("Json")) return "json";
        if (failure instanceof IllegalArgumentException) return "value";
        return sanitize(simple.isBlank() ? failure.getClass().getName() : simple);
    }

    private static String sanitize(String value) {
        StringBuilder out = new StringBuilder(Math.min(48, value.length()));
        for (int i = 0; i < value.length() && out.length() < 48; i++) {
            char c = value.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        return out.length() == 0 ? "unknown" : out.toString();
    }
}
