package com.asbestosstar.nativeaccelerator.renderer.universal.capability;

import java.util.Set;

public record CapabilityPlan(CapabilityOutcome outcome, String backend, Set<String> missing, String reason) {
    public CapabilityPlan {
        missing = Set.copyOf(missing == null ? Set.of() : missing);
        reason = reason == null ? "" : reason;
    }
}
