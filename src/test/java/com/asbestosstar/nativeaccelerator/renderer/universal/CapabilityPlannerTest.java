package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.capability.CapabilityOutcome;
import com.asbestosstar.nativeaccelerator.renderer.universal.capability.CapabilityPlanner;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdCapabilities;

import java.util.Set;

public final class CapabilityPlannerTest {
    public static void main(String[] args) {
        UrdCapabilities capabilities = new UrdCapabilities("fake", Set.of("derivatives"), 4, true);
        var planner = new CapabilityPlanner();
        if (planner.plan(capabilities, Set.of("derivatives")).outcome() != CapabilityOutcome.NATIVE) {
            throw new AssertionError("native capability not selected");
        }
        var missing = planner.plan(capabilities, Set.of("storage-images"));
        if (missing.outcome() != CapabilityOutcome.UNSUPPORTED || !missing.missing().contains("storage-images")) {
            throw new AssertionError("missing capability not reported");
        }
        System.out.println("CapabilityPlannerTest: PASS");
    }
}
