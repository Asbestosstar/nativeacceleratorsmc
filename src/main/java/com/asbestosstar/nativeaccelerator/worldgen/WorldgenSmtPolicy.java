package com.asbestosstar.nativeaccelerator.worldgen;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import com.asbestosstar.nativeaccelerator.platform.CpuTopology;
import com.asbestosstar.nativeaccelerator.platform.CpuTopologyDetector;

/** High-SMT occupancy policy. Values are deliberately benchmark-tunable instead of architecture hard-codes. */
public final class WorldgenSmtPolicy {
    private static final CpuTopology TOPOLOGY = CpuTopologyDetector.current();
    private WorldgenSmtPolicy() {}

    public static boolean enabled() {
        return NativeAcceleratorConfig.booleanValue("worldgen.smtScheduler", TOPOLOGY.highSmt());
    }

    public static int strandsPerCore() {
        int configured = NativeAcceleratorConfig.intValue("worldgen.smt.strandsPerCore", 0, 0);
        if (configured > 0) return Math.min(configured, TOPOLOGY.threadsPerCore());
        // SPARC T-series throughput generally benefits from deeper strand occupancy because each core
        // is designed around latency hiding. Keep one/two strands free of forced occupancy rather than
        // jumping straight to all eight, which preserves headroom for server/render/GC work.
        if (TOPOLOGY.isSparc() && TOPOLOGY.threadsPerCore() >= 8) return 6;
        return TOPOLOGY.threadsPerCore() >= 8 ? 4 : Math.min(2, TOPOLOGY.threadsPerCore());
    }

    public static int reservedCores() {
        return NativeAcceleratorConfig.intValue("worldgen.smt.reserveCores", TOPOLOGY.highSmt() ? 1 : 0, 0);
    }

    public static boolean bindWorkers() {
        return NativeAcceleratorConfig.booleanValue("worldgen.smt.bind", true);
    }

    public static boolean pinServerThread() {
        return NativeAcceleratorConfig.booleanValue("worldgen.smt.pinServerThread", true);
    }
}

