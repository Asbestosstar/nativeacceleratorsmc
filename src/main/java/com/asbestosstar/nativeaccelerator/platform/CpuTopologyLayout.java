package com.asbestosstar.nativeaccelerator.platform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Explicit physical-core -> logical-processor mapping used by high-SMT schedulers. */
public record CpuTopologyLayout(List<CpuCoreLayout> cores, String source, boolean exact) {
    public CpuTopologyLayout {
        ArrayList<CpuCoreLayout> copy = new ArrayList<>(cores == null ? List.of() : cores);
        copy.sort(Comparator.comparingInt(CpuCoreLayout::packageId).thenComparingInt(CpuCoreLayout::coreId));
        cores = List.copyOf(copy);
        source = source == null || source.isBlank() ? "unknown" : source;
    }

    /**
     * CPU ids ordered core-first, then sibling-strand level. For SMT8 this yields every core's first strand,
     * then every core's second strand, etc. This is the ordering wanted for throughput sweeps.
     */
    public int[] spreadOrder(int strandsPerCore, int reservedCores) {
        int reserve = Math.max(0, Math.min(reservedCores, cores.size()));
        List<CpuCoreLayout> usable = cores.subList(reserve, cores.size());
        int maxStrands = 0;
        for (CpuCoreLayout core : usable) maxStrands = Math.max(maxStrands, core.strandCount());
        int wanted = strandsPerCore <= 0 ? maxStrands : Math.min(strandsPerCore, maxStrands);
        ArrayList<Integer> ids = new ArrayList<>();
        for (int strand = 0; strand < wanted; ++strand) {
            for (CpuCoreLayout core : usable) {
                int[] logical = core.logicalProcessorIds();
                if (strand < logical.length) ids.add(logical[strand]);
            }
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    public int maxStrandsPerCore() {
        int max = 1;
        for (CpuCoreLayout core : cores) max = Math.max(max, core.strandCount());
        return max;
    }
}

