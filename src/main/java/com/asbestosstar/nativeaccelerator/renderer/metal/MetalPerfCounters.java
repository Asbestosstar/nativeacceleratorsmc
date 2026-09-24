package com.asbestosstar.nativeaccelerator.renderer.metal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Lightweight development telemetry for the Metal backend.
 *
 * <p>Disabled by default in production builds. Enable explicitly with
 * {@code -Dnativeaccelerator.renderer.metal.benchmark=true}. The console summary is intentionally
 * compact enough to paste into an issue. A CSV is also written under the game directory.</p>
 */
final class MetalPerfCounters {
    static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("nativeaccelerator.renderer.metal.benchmark", "false"));
    private static final int REPORT_FRAMES = Math.max(30, Integer.getInteger(
            "nativeaccelerator.renderer.metal.benchmarkFrames", 120));

    private static long intervalStartNs = System.nanoTime();
    private static long lastPresentNs;
    private static long frames;
    private static long frameNs;
    private static long maxFrameNs;

    private static long draws, indexedDraws, terrainDraws;
    private static long nativeIndirectCalls, cpuIndirectCommands;
    private static long drawNs;
    private static long pipelineBinds, pipelineBindSkips;
    private static long vertexBinds, vertexBindSkips, indexBinds, indexBindSkips;
    private static long uniformPushes, uniformBytes, uniformPushNs, terrainUniformPushes;
    private static long samplerBinds, storageBinds;
    private static long renderPasses, renderPassNs;
    private static long dirtyFlushes, dirtyBuffers, dirtyBytes, dirtyFlushNs;
    private static long textureUploads, textureUploadBytes;
    private static long submits, submitNs;
    private static long fenceCreates, fenceQueries, fenceWaits, fenceWaitNs;
    private static long swapAcquires, swapWaitNs, blits, blitNs;
    private static long pipelineCompiles, pipelineCompileNs;
    private static long interopCalls, interopNs;

    private static BufferedWriter csv;
    private static boolean csvFailed;

    private MetalPerfCounters() {}

    static long tic() { return ENABLED ? System.nanoTime() : 0L; }
    private static long elapsed(long start) { return ENABLED && start != 0L ? System.nanoTime() - start : 0L; }

    static void draw(boolean indexed, boolean terrain, long start) { if (!ENABLED) return; draws++; if (indexed) indexedDraws++; if (terrain) terrainDraws++; drawNs += elapsed(start); }
    static void nativeIndirectCall() { if (ENABLED) nativeIndirectCalls++; }
    static void cpuIndirectCommand() { if (ENABLED) cpuIndirectCommands++; }
    static void pipelineBind(long start) { if (!ENABLED) return; pipelineBinds++; }
    static void pipelineBindSkip() { if (ENABLED) pipelineBindSkips++; }
    static void vertexBind() { if (ENABLED) vertexBinds++; }
    static void vertexBindSkip() { if (ENABLED) vertexBindSkips++; }
    static void indexBind() { if (ENABLED) indexBinds++; }
    static void indexBindSkip() { if (ENABLED) indexBindSkips++; }
    static void uniformPush(int bytes, boolean terrain, long start) { if (!ENABLED) return; uniformPushes++; if (terrain) terrainUniformPushes++; uniformBytes += Math.max(0, bytes); uniformPushNs += elapsed(start); }
    static void samplerBind() { if (ENABLED) samplerBinds++; }
    static void storageBind() { if (ENABLED) storageBinds++; }
    static void renderPass(long start) { if (!ENABLED) return; renderPasses++; renderPassNs += elapsed(start); }
    static void dirtyFlush(int buffers, long bytes, long start) { if (!ENABLED) return; dirtyFlushes++; dirtyBuffers += buffers; dirtyBytes += bytes; dirtyFlushNs += elapsed(start); }
    static void textureUpload(int bytes) { if (!ENABLED) return; textureUploads++; textureUploadBytes += Math.max(0, bytes); }
    static void submit(long start) { if (!ENABLED) return; submits++; submitNs += elapsed(start); }
    static void fenceCreate() { if (ENABLED) fenceCreates++; }
    static void fenceQuery() { if (ENABLED) fenceQueries++; }
    static void fenceWait(long start) { if (!ENABLED) return; fenceWaits++; fenceWaitNs += elapsed(start); }
    static void swapAcquire(long start) { if (!ENABLED) return; swapAcquires++; swapWaitNs += elapsed(start); }
    static void blit(long start) { if (!ENABLED) return; blits++; blitNs += elapsed(start); }
    static void pipelineCompile(long start) { if (!ENABLED) return; pipelineCompiles++; pipelineCompileNs += elapsed(start); }
    static void interop(long start) { if (!ENABLED) return; interopCalls++; interopNs += elapsed(start); }

    static void present() {
        if (!ENABLED) return;
        long now = System.nanoTime();
        if (lastPresentNs != 0L) {
            long d = now - lastPresentNs;
            frameNs += d;
            maxFrameNs = Math.max(maxFrameNs, d);
        }
        lastPresentNs = now;
        frames++;
        if (frames >= REPORT_FRAMES) report(now);
    }

    private static synchronized void report(long now) {
        if (frames == 0) return;
        double intervalSec = Math.max(1e-9, (now - intervalStartNs) / 1_000_000_000.0);
        double fps = frames / intervalSec;
        double denom = Math.max(1.0, frames);
        double avgFrameMs = frameNs == 0 ? 0.0 : frameNs / Math.max(1.0, frames - 1.0) / 1_000_000.0;
        String line = String.format(Locale.ROOT,
                "[Native Accelerator][MetalPerf] fps=%.1f frame=%.2fms max=%.2fms draws=%.0f/f terrain=%.0f/f indexed=%.0f/f indirectNative=%.2f/f indirectCPU=%.0f/f " +
                "uboPush=%.0f/f(terrain=%.0f) %.1fKiB/f pipe=%.0f/f vb=%.0f/f(vbSkip=%.0f) pass=%.1f/f " +
                "flush=%.2f/f %.1fKiB/f submit=%.2f/f fences=%.2f/f wait=%.2fms/f swapWait=%.2fms/f " +
                "drawCPU=%.2fms/f uniformCPU=%.2fms/f interop=%.2fms/f",
                fps, avgFrameMs, maxFrameNs / 1_000_000.0,
                draws / denom, terrainDraws / denom, indexedDraws / denom, nativeIndirectCalls / denom, cpuIndirectCommands / denom,
                uniformPushes / denom, terrainUniformPushes / denom, uniformBytes / denom / 1024.0,
                pipelineBinds / denom, vertexBinds / denom, vertexBindSkips / denom, renderPasses / denom,
                dirtyFlushes / denom, dirtyBytes / denom / 1024.0, submits / denom,
                fenceCreates / denom, fenceWaitNs / denom / 1_000_000.0, swapWaitNs / denom / 1_000_000.0,
                drawNs / denom / 1_000_000.0, uniformPushNs / denom / 1_000_000.0,
                interopNs / denom / 1_000_000.0);
        System.out.println(line);
        appendCsv(now, fps, avgFrameMs);
        reset(now);
    }

    private static void appendCsv(long now, double fps, double avgFrameMs) {
        if (csvFailed) return;
        try {
            if (csv == null) {
                Path file = benchmarkFile();
                Files.createDirectories(file.getParent());
                boolean empty = !Files.exists(file) || Files.size(file) == 0L;
                csv = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (empty) {
                    csv.write("time,fps,avg_frame_ms,max_frame_ms,draws_per_frame,terrain_draws_per_frame,indexed_draws_per_frame,native_indirect_calls_per_frame,cpu_indirect_commands_per_frame,uniform_pushes_per_frame,terrain_uniform_pushes_per_frame,uniform_kib_per_frame,pipeline_binds_per_frame,vertex_binds_per_frame,vertex_bind_skips_per_frame,index_binds_per_frame,index_bind_skips_per_frame,render_passes_per_frame,dirty_flushes_per_frame,dirty_buffers_per_frame,dirty_kib_per_frame,submits_per_frame,fences_per_frame,fence_queries_per_frame,fence_waits_per_frame,fence_wait_ms_per_frame,swap_wait_ms_per_frame,blit_ms_per_frame,draw_cpu_ms_per_frame,uniform_cpu_ms_per_frame,dirty_flush_cpu_ms_per_frame,submit_cpu_ms_per_frame,interop_calls_per_frame,interop_cpu_ms_per_frame,pipeline_compiles,pipeline_compile_ms,texture_uploads,texture_upload_kib\n");
                }
                System.out.println("[Native Accelerator][MetalPerf] CSV: " + file.toAbsolutePath());
            }
            double d = Math.max(1.0, frames);
            csv.write(String.format(Locale.ROOT,
                    "%s,%.3f,%.4f,%.4f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.3f,%.4f,%d,%.4f,%d,%.3f%n",
                    Instant.now(), fps, avgFrameMs, maxFrameNs / 1_000_000.0,
                    draws/d, terrainDraws/d, indexedDraws/d, nativeIndirectCalls/d, cpuIndirectCommands/d,
                    uniformPushes/d, terrainUniformPushes/d, uniformBytes/d/1024.0,
                    pipelineBinds/d, vertexBinds/d, vertexBindSkips/d, indexBinds/d, indexBindSkips/d,
                    renderPasses/d, dirtyFlushes/d, dirtyBuffers/d, dirtyBytes/d/1024.0,
                    submits/d, fenceCreates/d, fenceQueries/d, fenceWaits/d, fenceWaitNs/d/1_000_000.0,
                    swapWaitNs/d/1_000_000.0, blitNs/d/1_000_000.0, drawNs/d/1_000_000.0,
                    uniformPushNs/d/1_000_000.0, dirtyFlushNs/d/1_000_000.0, submitNs/d/1_000_000.0,
                    interopCalls/d, interopNs/d/1_000_000.0, pipelineCompiles, pipelineCompileNs/1_000_000.0,
                    textureUploads, textureUploadBytes/1024.0));
            csv.flush();
        } catch (Throwable t) {
            csvFailed = true;
            System.err.println("[Native Accelerator][MetalPerf] Could not write CSV: " + t);
            try { if (csv != null) csv.close(); } catch (IOException ignored) {}
            csv = null;
        }
    }

    private static Path benchmarkFile() {
        String override = System.getProperty("nativeaccelerator.renderer.metal.benchmarkFile", "").trim();
        if (!override.isEmpty()) return Path.of(override).toAbsolutePath();
        String gameDir = System.getProperty("minecraft.applet.TargetDirectory", ".");
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.systemDefault()).format(Instant.now());
        return Path.of(gameDir).toAbsolutePath().resolve(".nativeaccelerator").resolve("benchmarks")
                .resolve("metal-perf-" + stamp + ".csv");
    }

    private static void reset(long now) {
        intervalStartNs = now;
        frames=frameNs=maxFrameNs=0L;
        draws=indexedDraws=terrainDraws=drawNs=0L;
        nativeIndirectCalls=cpuIndirectCommands=0L;
        pipelineBinds=pipelineBindSkips=0L;
        vertexBinds=vertexBindSkips=indexBinds=indexBindSkips=0L;
        uniformPushes=uniformBytes=uniformPushNs=terrainUniformPushes=0L;
        samplerBinds=storageBinds=0L;
        renderPasses=renderPassNs=0L;
        dirtyFlushes=dirtyBuffers=dirtyBytes=dirtyFlushNs=0L;
        textureUploads=textureUploadBytes=0L;
        submits=submitNs=0L;
        fenceCreates=fenceQueries=fenceWaits=fenceWaitNs=0L;
        swapAcquires=swapWaitNs=blits=blitNs=0L;
        pipelineCompiles=pipelineCompileNs=0L;
        interopCalls=interopNs=0L;
    }
}

