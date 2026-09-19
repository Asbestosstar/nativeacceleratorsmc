package com.asbestosstar.nativeaccelerator.platform;

import java.util.List;
import java.util.Arrays;

/** Dependency-free checks for core-first SMT ordering and core reservation. */
public final class CpuTopologyLayoutTest {
    private CpuTopologyLayoutTest() {}

    public static void main(String[] args) {
        CpuTopologyLayout t8 = new CpuTopologyLayout(List.of(
                new CpuCoreLayout(0, 0, new int[]{0,1,2,3,4,5,6,7}),
                new CpuCoreLayout(0, 1, new int[]{8,9,10,11,12,13,14,15}),
                new CpuCoreLayout(0, 2, new int[]{16,17,18,19,20,21,22,23})
        ), "test-t8", true);

        expect("SMT4 spreads cores before sibling strands",
                new int[]{0,8,16,1,9,17,2,10,18,3,11,19}, t8.spreadOrder(4, 0));
        expect("reserve one physical core",
                new int[]{8,16,9,17,10,18,11,19}, t8.spreadOrder(4, 1));
        expect("SMT8 includes every visible strand",
                new int[]{0,8,16,1,9,17,2,10,18,3,11,19,4,12,20,5,13,21,6,14,22,7,15,23},
                t8.spreadOrder(8, 0));
        expect("request above hardware SMT clamps", t8.spreadOrder(8, 0), t8.spreadOrder(64, 0));
        expect("reserve all cores gives no workers", new int[0], t8.spreadOrder(4, 99));

        CpuTopologyLayout uneven = new CpuTopologyLayout(List.of(
                new CpuCoreLayout(0, 0, new int[]{4,2}),
                new CpuCoreLayout(0, 1, new int[]{9})
        ), "uneven", true);
        expect("logical ids are sorted and missing siblings skipped", new int[]{2,9,4}, uneven.spreadOrder(2,0));

        System.out.println("CpuTopologyLayoutTest: PASS");
    }

    private static void expect(String name, int[] expected, int[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(name + ": expected=" + Arrays.toString(expected)
                    + " actual=" + Arrays.toString(actual));
        }
    }
}
