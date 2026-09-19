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
        // Safe starting occupancy for SMT4/8; calibration should sweep 1/2/4/6/8 on the real host.
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
