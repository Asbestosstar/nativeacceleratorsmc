package com.asbestosstar.nativeaccelerator.platform;

/** Architecture/topology-aware defaults for the two startup worker domains. */
public final class CpuSchedulerPolicy {
    private CpuSchedulerPolicy() {}

    /**
     * Model parsing is throughput-oriented.  Ordinary SMT2 CPUs may use all JVM-visible logical CPUs;
     * high-strand designs (SPARC M8, POWER SMT4/8, etc.) start at two strands per effective physical core
     * rather than treating every strand as an independent core.
     */
    public static int modelWorkers(CpuTopology topology) {
        int visible = Math.max(1, topology.jvmAvailableProcessors());
        int physical = Math.max(1, topology.effectivePhysicalCores());
        if (topology.threadsPerCore() >= 4) {
            return Math.max(1, Math.min(visible, physical * 2));
        }
        return visible;
    }

    /**
     * Atlas work is deliberately bounded so PNG/mipmap preparation does not steal the model critical path.
     * On conventional SMT1/2 systems the measured sweet spot is one worker per physical core, capped at 8.
     * High-strand CPUs use a conservative subset of physical cores; SPARC/POWER can still exploit additional
     * strands through the model pool without launching dozens of competing atlas workers.
     */
    public static int atlasWorkers(CpuTopology topology) {
        int physical = Math.max(1, topology.effectivePhysicalCores());
        int visible = Math.max(1, topology.jvmAvailableProcessors());
        int workers;
        if (topology.threadsPerCore() >= 4) {
            int packageFloor = Math.max(1, topology.packages() * 2);
            workers = Math.max(1, Math.min(16, Math.max(packageFloor, Math.max(2, physical / 2))));
        } else {
            workers = Math.max(1, Math.min(8, physical));
        }
        return Math.min(visible, workers);
    }

    /**
     * Per-core clock is only a same-architecture overhead hint.  It adjusts when tiny mip jobs become worth
     * parallelising; it does not compare IPC between SPARC, x86, ARM or POWER and never changes core counts.
     */
    public static int mipmapSerialPixels(CpuTopology topology, int basePixels) {
        int mhz = topology.referenceClockMHz();
        if (mhz <= 0 || !(topology.architecture().equals("amd64") || topology.architecture().equals("arm64"))) {
            return Math.max(0, basePixels);
        }
        double scale = Math.max(0.65, Math.min(1.50, mhz / 2400.0));
        long adjusted = Math.round(basePixels * scale);
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, adjusted));
    }
}
