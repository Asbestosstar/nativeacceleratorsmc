package com.asbestosstar.nativeaccelerator.renderer.universal.capability;

import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdCapabilities;

import java.util.LinkedHashSet;
import java.util.Set;

public final class CapabilityPlanner {
    public CapabilityPlan plan(UrdCapabilities capabilities, Set<String> required) {
        Set<String> missing = new LinkedHashSet<>();
        for (String feature : required == null ? Set.<String>of() : required) {
            if (!capabilities.supports(feature)) missing.add(feature);
        }
        if (!missing.isEmpty()) {
            return new CapabilityPlan(CapabilityOutcome.UNSUPPORTED, capabilities.backend(), missing,
                    "backend lacks required semantics");
        }
        return new CapabilityPlan(CapabilityOutcome.NATIVE, capabilities.backend(), Set.of(), "");
    }
}
