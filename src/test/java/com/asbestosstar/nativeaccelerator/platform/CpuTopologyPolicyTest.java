package com.asbestosstar.nativeaccelerator.platform;

/** Dependency-free regression harness for topology discovery policy. */
public final class CpuTopologyPolicyTest {
    private CpuTopologyPolicyTest() {}

    public static void main(String[] args) {
        intelMacBookPolicy();
        sparcT8Policy();
        processorSetScaling();
        solarisKstatParsing();
        clockOnlyChangesMipmapThreshold();
        System.out.println("CpuTopologyPolicyTest: PASS");
    }

    private static void intelMacBookPolicy() {
        CpuTopology t = new CpuTopology(1, 4, 8, 8, 2400, 4100, "amd64", "test");
        check(t.threadsPerCore() == 2, "MBP SMT ratio");
        check(t.effectivePhysicalCores() == 4, "MBP effective cores");
        check(CpuSchedulerPolicy.atlasWorkers(t) == 4, "MBP atlas workers");
        check(CpuSchedulerPolicy.modelWorkers(t) == 8, "MBP model workers");
        check(CpuSchedulerPolicy.mipmapSerialPixels(t, 384 * 1024) == 384 * 1024,
                "2.4 GHz baseline threshold");
    }

    private static void sparcT8Policy() {
        CpuTopology t = new CpuTopology(1, 32, 256, 256, 5000, 5000, "sparcv9", "test");
        check(t.threadsPerCore() == 8, "T8 strands/core");
        check(CpuSchedulerPolicy.atlasWorkers(t) == 16, "T8 bounded atlas workers");
        check(CpuSchedulerPolicy.modelWorkers(t) == 64, "T8 model workers use two strands/core");
        check(CpuSchedulerPolicy.mipmapSerialPixels(t, 384 * 1024) == 384 * 1024,
                "clock is not compared cross-ISA");
    }

    private static void processorSetScaling() {
        CpuTopology t = new CpuTopology(1, 32, 256, 64, 5000, 5000, "sparcv9", "test");
        check(t.effectivePhysicalCores() == 8, "processor-set physical scaling");
        check(CpuSchedulerPolicy.atlasWorkers(t) == 4, "processor-set atlas workers");
        check(CpuSchedulerPolicy.modelWorkers(t) == 16, "processor-set model workers");
    }

    private static void solarisKstatParsing() {
        StringBuilder sample = new StringBuilder();
        for (int strand = 0; strand < 16; strand++) {
            int core = strand / 8;
            sample.append("cpu_info:").append(strand).append(":cpu_info").append(strand)
                    .append(":chip_id\t0\n");
            sample.append("cpu_info:").append(strand).append(":cpu_info").append(strand)
                    .append(":core_id\t").append(core).append('\n');
            sample.append("cpu_info:").append(strand).append(":cpu_info").append(strand)
                    .append(":clock_MHz\t5000\n");
        }
        CpuTopology t = CpuTopologyDetector.parseSolarisKstatForTest(sample.toString(), 16, "sparcv9");
        check(t.packages() == 1, "kstat packages");
        check(t.physicalCores() == 2, "kstat cores");
        check(t.logicalProcessors() == 16, "kstat strands");
        check(t.threadsPerCore() == 8, "kstat strands/core");
        check(t.maxClockMHz() == 5000, "kstat clock");
    }

    private static void clockOnlyChangesMipmapThreshold() {
        CpuTopology slow = new CpuTopology(1, 4, 8, 8, 1600, 3000, "amd64", "test");
        CpuTopology fast = new CpuTopology(1, 4, 8, 8, 4000, 5000, "amd64", "test");
        check(CpuSchedulerPolicy.atlasWorkers(slow) == CpuSchedulerPolicy.atlasWorkers(fast),
                "clock must not invent cores");
        check(CpuSchedulerPolicy.mipmapSerialPixels(slow, 384 * 1024)
                        < CpuSchedulerPolicy.mipmapSerialPixels(fast, 384 * 1024),
                "clock adjusts only parallel overhead threshold");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
