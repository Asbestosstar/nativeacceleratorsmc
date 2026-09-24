package com.asbestosstar.nativeaccelerator.kernels;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import com.asbestosstar.nativeaccelerator.platform.CpuTopology;
import com.asbestosstar.nativeaccelerator.platform.CpuTopologyDetector;

import java.util.concurrent.Semaphore;

/** Bounds DAX submissions without letting SMT8 worker counts turn into hundreds of device requests. */
final class DaxConcurrencyLimiter {
    private static final int MAX = configuredMaximum();
    private static final Semaphore PERMITS = new Semaphore(MAX, false);

    private DaxConcurrencyLimiter() {}

    static boolean tryEnter() { return PERMITS.tryAcquire(); }
    static void exit() { PERMITS.release(); }
    static int maximum() { return MAX; }

    private static int configuredMaximum() {
        String configured = NativeAcceleratorConfig.stringValue("dax.maxConcurrent", "auto").trim();
        if (!configured.isEmpty() && !configured.equalsIgnoreCase("auto")) {
            try { return Math.max(1, Math.min(64, Integer.parseInt(configured))); }
            catch (NumberFormatException ignored) {}
        }
        CpuTopology topology = CpuTopologyDetector.current();
        // DAX benefits from queued work, but one request per SPARC strand is counterproductive.
        // Scale with physical cores and cap device pressure; SPARC SMT8 hosts usually land at 16.
        int physical = Math.max(1, topology.effectivePhysicalCores());
        int target = topology.highSmt() ? Math.max(8, physical / 2) : Math.max(4, physical / 2);
        return Math.max(4, Math.min(16, target));
    }
}
