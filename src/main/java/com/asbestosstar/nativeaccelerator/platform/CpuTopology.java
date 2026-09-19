package com.asbestosstar.nativeaccelerator.platform;

/**
 * CPU topology as reported by the host OS, kept separate from the JVM's process-visible CPU budget.
 *
 * <p>{@code logicalProcessors} is the machine-wide hardware-thread/strand count when the OS exposes it;
 * {@code jvmAvailableProcessors} is the number Java says this process may actually schedule on. Scheduler
 * policy must respect the latter even when the host contains more CPUs (containers, processor sets, affinity,
 * zones, etc.). Clock values are informational/per-core scheduling hints only; they are never treated as a
 * cross-ISA measure of performance.</p>
 */
public record CpuTopology(
        int packages,
        int physicalCores,
        int logicalProcessors,
        int jvmAvailableProcessors,
        int nominalClockMHz,
        int maxClockMHz,
        String architecture,
        String source) {

    public CpuTopology {
        packages = positiveOr(packages, 1);
        logicalProcessors = positiveOr(logicalProcessors, Math.max(1, jvmAvailableProcessors));
        jvmAvailableProcessors = positiveOr(jvmAvailableProcessors, logicalProcessors);
        physicalCores = positiveOr(physicalCores, Math.min(logicalProcessors, jvmAvailableProcessors));
        physicalCores = Math.min(physicalCores, logicalProcessors);
        nominalClockMHz = Math.max(0, nominalClockMHz);
        maxClockMHz = Math.max(nominalClockMHz, maxClockMHz);
        architecture = architecture == null || architecture.isBlank() ? "unknown" : architecture;
        source = source == null || source.isBlank() ? "jvm" : source;
    }

    public int threadsPerCore() {
        return Math.max(1, (logicalProcessors + physicalCores - 1) / physicalCores);
    }

    /** Estimated physical cores inside the CPU set Java can actually use. */
    public int effectivePhysicalCores() {
        if (jvmAvailableProcessors >= logicalProcessors) return Math.min(physicalCores, jvmAvailableProcessors);
        long scaled = ((long) physicalCores * jvmAvailableProcessors + logicalProcessors - 1L) / logicalProcessors;
        return Math.max(1, Math.min(jvmAvailableProcessors, (int) Math.min(Integer.MAX_VALUE, scaled)));
    }

    /** Stable-ish frequency hint: prefer nominal/base clock over transient turbo/current frequency. */
    public int referenceClockMHz() {
        return nominalClockMHz > 0 ? nominalClockMHz : maxClockMHz;
    }

    public boolean highSmt() {
        return threadsPerCore() >= 4;
    }

    public boolean isSparc() {
        return architecture.equals("sparcv9") || architecture.startsWith("sparc");
    }

    private static int positiveOr(int value, int fallback) {
        return value > 0 ? value : Math.max(1, fallback);
    }
}

