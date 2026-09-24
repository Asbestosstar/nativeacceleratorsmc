package com.asbestosstar.nativeaccelerator.platform;

import java.util.Arrays;

/** One physical core and the OS logical-processor/strand ids that belong to it. */
public record CpuCoreLayout(int packageId, int coreId, int[] logicalProcessorIds) {
    public CpuCoreLayout {
        logicalProcessorIds = logicalProcessorIds == null ? new int[0] : logicalProcessorIds.clone();
        Arrays.sort(logicalProcessorIds);
    }

    @Override
    public int[] logicalProcessorIds() {
        return logicalProcessorIds.clone();
    }

    public int strandCount() {
        return logicalProcessorIds.length;
    }
}

