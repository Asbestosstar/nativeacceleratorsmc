package com.asbestosstar.nativeaccelerator.worldgen;

import com.asbestosstar.nativeaccelerator.platform.CpuCoreLayout;
import com.asbestosstar.nativeaccelerator.platform.CpuTopologyDetector;
import com.asbestosstar.nativeaccelerator.platform.CpuTopologyLayout;
import com.asbestosstar.nativeaccelerator.platform.CpuTopologyLayoutDetector;
import com.asbestosstar.nativeaccelerator.platform.Platform;
import com.asbestosstar.nativeaccelerator.platform.SolarisThreadAffinity;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/** Dedicated terrain scheduler with core-first SMT occupancy, local deques/work stealing and Solaris affinity. */
public final class WorldgenWorkScheduler {
    private static final class Holder { static final State STATE = create(); }
    private WorldgenWorkScheduler() {}

    public static boolean enabled() { return WorldgenSmtPolicy.enabled(); }
    public static Executor executor() { return Holder.STATE.executor; }
    public static int workers() { return Holder.STATE.workers; }
    public static String describe() { return Holder.STATE.description; }

    /** Best-effort dedicated-server main-thread binding to the first reserved physical core. */
    public static boolean bindServerThread() {
        if (!enabled() || !WorldgenSmtPolicy.pinServerThread() || !"solaris".equals(Platform.current().os())) return false;
        CpuTopologyLayout layout = CpuTopologyLayoutDetector.current();
        if (!layout.exact() || layout.cores().isEmpty() || WorldgenSmtPolicy.reservedCores() <= 0) return false;
        CpuCoreLayout core = layout.cores().getFirst();
        int[] cpus = core.logicalProcessorIds();
        if (cpus.length == 0) return false;
        boolean bound = SolarisThreadAffinity.bindCurrent(cpus[0]);
        if (bound) System.out.println("[Native Accelerator] dedicated server thread bound to reserved cpu " + cpus[0]);
        return bound;
    }

    private static State create() {
        if (!enabled()) return new State(Runnable::run, 0, "disabled");
        CpuTopologyLayout layout = CpuTopologyLayoutDetector.current();
        int[] order = layout.spreadOrder(WorldgenSmtPolicy.strandsPerCore(), WorldgenSmtPolicy.reservedCores());
        int visible = Math.max(1, CpuTopologyDetector.current().jvmAvailableProcessors());
        int workers = Math.max(1, Math.min(visible, order.length));
        final int finalWorkers = workers;
        AtomicInteger ids = new AtomicInteger();
        ForkJoinPool pool = new ForkJoinPool(workers, owner -> {
            int worker = ids.getAndIncrement();
            int cpu = order.length == 0 ? -1 : order[worker % Math.min(order.length, finalWorkers)];
            ForkJoinWorkerThread thread = new ForkJoinWorkerThread(owner) {
                @Override protected void onStart() {
                    super.onStart();
                    if (cpu >= 0 && WorldgenSmtPolicy.bindWorkers() && "solaris".equals(Platform.current().os())) {
                        SolarisThreadAffinity.bindCurrent(cpu);
                    }
                }
            };
            thread.setDaemon(true);
            thread.setName("NativeAccelerator-Worldgen-" + worker + (cpu >= 0 ? "-cpu" + cpu : ""));
            return thread;
        }, (thread, failure) -> System.err.println("[Native Accelerator] worldgen worker failed: " + failure), true);
        String description = "workers=" + workers + " strands/core=" + WorldgenSmtPolicy.strandsPerCore()
                + " reserveCores=" + WorldgenSmtPolicy.reservedCores() + " layout=" + layout.source()
                + " exact=" + layout.exact() + " bind=" + WorldgenSmtPolicy.bindWorkers();
        System.out.println("[Native Accelerator] worldgen SMT scheduler: " + description);
        return new State(pool, workers, description);
    }

    private record State(Executor executor, int workers, String description) {}
}

