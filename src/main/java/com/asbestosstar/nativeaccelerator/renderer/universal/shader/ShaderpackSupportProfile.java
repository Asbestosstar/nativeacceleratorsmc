package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, loader-neutral description of a shaderpack's portability requirements and negotiation
 * result for one renderer backend.
 *
 * <p>This model deliberately does not mention Minecraft, a loader, or a graphics API. Frontends and
 * backends can map their language/profile/resource details onto these semantic requirements without
 * coupling the negotiation policy to either side.</p>
 */
public final class ShaderpackSupportProfile {
    private final String name;
    private final Set<ShaderpackCapability> required;
    private final Map<ShaderpackCapability, ShaderpackCapabilityResult> results;
    private final ShaderpackOutcome outcome;
    private final List<String> diagnostics;

    private ShaderpackSupportProfile(String name,
            Set<ShaderpackCapability> required,
            Map<ShaderpackCapability, ShaderpackCapabilityResult> results,
            ShaderpackOutcome outcome,
            List<String> diagnostics) {
        this.name = name;
        this.required = Set.copyOf(required);
        this.results = Map.copyOf(results);
        this.outcome = outcome;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public Set<ShaderpackCapability> requiredCapabilities() {
        return required;
    }

    /** Results are present for every required capability after {@link Builder#negotiate}. */
    public Map<ShaderpackCapability, ShaderpackCapabilityResult> capabilityResults() {
        return results;
    }

    public ShaderpackOutcome outcome() {
        return outcome;
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    public boolean acceptedByDefault() {
        return outcome.acceptedByDefault();
    }

    public boolean accepted(boolean approximationOptIn) {
        return outcome != ShaderpackOutcome.UNSUPPORTED
                && outcome != ShaderpackOutcome.BLOCKED
                && outcome != ShaderpackOutcome.INVALID
                && outcome != ShaderpackOutcome.FRONTEND_ONLY
                && (outcome != ShaderpackOutcome.APPROXIMATION_OPT_IN || approximationOptIn);
    }

    public ShaderpackCapabilityResult result(ShaderpackCapability capability) {
        Objects.requireNonNull(capability, "capability");
        return results.get(capability);
    }

    /** Mutable construction and capability negotiation helper. */
    public static final class Builder {
        private final String name;
        private final EnumSet<ShaderpackCapability> required = EnumSet.noneOf(ShaderpackCapability.class);
        private final EnumMap<ShaderpackCapability, ShaderpackCapabilityResult> results =
                new EnumMap<>(ShaderpackCapability.class);
        private final java.util.ArrayList<String> diagnostics = new java.util.ArrayList<>();

        private Builder(String name) {
            this.name = requireName(name);
        }

        public Builder requires(ShaderpackCapability capability) {
            required.add(Objects.requireNonNull(capability, "capability"));
            return this;
        }

        public Builder requires(ShaderpackCapability... capabilities) {
            Objects.requireNonNull(capabilities, "capabilities");
            for (ShaderpackCapability capability : capabilities) requires(capability);
            return this;
        }

        public Builder diagnostic(String diagnostic) {
            if (diagnostic != null && !diagnostic.isBlank()) diagnostics.add(diagnostic.trim());
            return this;
        }

        /**
         * Evaluates every required capability. Missing entries are treated as unavailable, so a
         * backend cannot accidentally claim support by omitting a requirement from its response.
         */
        public ShaderpackSupportProfile negotiate(Map<ShaderpackCapability, ShaderpackCapabilityState> support) {
            Objects.requireNonNull(support, "support");
            results.clear();
            diagnostics.removeIf(value -> value.startsWith("Unsupported capability: "));
            ShaderpackOutcome worst = ShaderpackOutcome.NATIVE;
            for (ShaderpackCapability capability : required) {
                ShaderpackCapabilityState state = support.getOrDefault(
                        capability, ShaderpackCapabilityState.UNAVAILABLE);
                ShaderpackCapabilityResult result = new ShaderpackCapabilityResult(capability, state, "");
                results.put(capability, result);
                worst = worst(worst, result.outcome());
                if (result.outcome() == ShaderpackOutcome.UNSUPPORTED) {
                    diagnostics.add("Unsupported capability: " + capability.id());
                }
            }
            return new ShaderpackSupportProfile(name, required, results, worst, diagnostics);
        }

        /** Negotiates a profile with no supported capabilities. */
        public ShaderpackSupportProfile unsupported() {
            return negotiate(Map.of());
        }

        private static String requireName(String name) {
            Objects.requireNonNull(name, "name");
            String normalized = name.trim();
            if (normalized.isEmpty()) throw new IllegalArgumentException("name must not be blank");
            return normalized;
        }

        private static ShaderpackOutcome worst(ShaderpackOutcome left, ShaderpackOutcome right) {
            return rank(right) > rank(left) ? right : left;
        }

        private static int rank(ShaderpackOutcome outcome) {
            return switch (outcome) {
                case NATIVE -> 0;
                case EXACT_TRANSLATION -> 1;
                case SEMANTIC_EMULATION -> 2;
                case APPROXIMATION_OPT_IN -> 3;
                case UNSUPPORTED -> 4;
                case BLOCKED -> 5;
                case FRONTEND_ONLY -> 6;
                case INVALID -> 7;
            };
        }
    }
}
