package com.asbestosstar.nativeaccelerator.renderer.metal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Very high volume asynchronous diagnostic trace for the Metal backend.
 *
 * IMPORTANT: this intentionally does NOT print each event to stdout. The user observed that
 * synchronous console printing makes the visual corruption much easier to trigger, so the render
 * thread only formats/enqueues records. A daemon writer thread performs disk I/O.
 */
final class MetalTrace {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(
            "nativeaccelerator.renderer.metal.trace", "false"));
    private static final boolean CONSOLE = Boolean.parseBoolean(System.getProperty(
            "nativeaccelerator.renderer.metal.traceConsole", "false"));
    private static final int QUEUE_SIZE = Integer.getInteger(
            "nativeaccelerator.renderer.metal.traceQueueSize", 262_144);
    private static final ArrayBlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(Math.max(4096, QUEUE_SIZE));
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(true);
    private static final AtomicLong EVENT = new AtomicLong();
    private static final AtomicLong FRAME = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static volatile Path path;

    private MetalTrace() {}

    static boolean enabled() { return ENABLED; }
    static long frame() { return FRAME.get(); }
    static void frame(long frame) { FRAME.set(frame); }

    static void log(String category, String message) {
        if (!ENABLED) return;
        ensureStarted();
        long id = EVENT.incrementAndGet();
        String line = System.nanoTime() + " event=" + id + " frame=" + FRAME.get()
                + " thread=\"" + Thread.currentThread().getName() + "\" "
                + category + " " + message;
        if (!QUEUE.offer(line)) DROPPED.incrementAndGet();
        if (CONSOLE) System.out.println("[Native Accelerator][MetalTrace] " + line);
    }

    static void logError(String category, Throwable failure) {
        if (!ENABLED) return;
        log(category, "ERROR type=" + failure.getClass().getName() + " message=\""
                + safe(failure.getMessage()) + "\"");
        StackTraceElement[] stack = failure.getStackTrace();
        for (int i = 0; i < Math.min(stack.length, 16); i++) log(category, "STACK " + stack[i]);
    }

    static String hex(long value) { return "0x" + Long.toHexString(value); }
    static String safe(Object value) {
        if (value == null) return "null";
        return String.valueOf(value).replace('\\', '/').replace('\n', ' ').replace('\r', ' ').replace('"', '\'');
    }

    static Path path() { ensureStarted(); return path; }

    private static void ensureStarted() {
        if (!ENABLED || !STARTED.compareAndSet(false, true)) return;
        try {
            Path logs = Path.of(System.getProperty("user.dir", "."), "logs");
            Files.createDirectories(logs);
            path = logs.resolve("nativeaccelerator-metal-trace.log");
            Thread writer = new Thread(MetalTrace::writerLoop, "NativeAccelerator-MetalTrace");
            writer.setDaemon(true);
            writer.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                RUNNING.set(false);
                try { writer.join(1500L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }, "NativeAccelerator-MetalTrace-Shutdown"));
            QUEUE.offer("# Native Accelerator Metal trace started " + Instant.now()
                    + " user.dir=" + System.getProperty("user.dir", ".")
                    + " queue=" + QUEUE.size() + "/" + QUEUE_SIZE);
        } catch (Throwable t) {
            path = null;
            if (CONSOLE) System.err.println("[Native Accelerator][MetalTrace] failed to start: " + t);
        }
    }

    private static void writerLoop() {
        if (path == null) return;
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            long written = 0;
            while (RUNNING.get() || !QUEUE.isEmpty()) {
                String line = QUEUE.poll(100, TimeUnit.MILLISECONDS);
                if (line != null) {
                    out.write(line);
                    out.newLine();
                    written++;
                }
                long dropped = DROPPED.getAndSet(0);
                if (dropped != 0) {
                    out.write(System.nanoTime() + " TRACE_DROPPED count=" + dropped);
                    out.newLine();
                }
                if ((written & 0xFF) == 0 || line == null) out.flush();
            }
            out.flush();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
        }
    }
}

