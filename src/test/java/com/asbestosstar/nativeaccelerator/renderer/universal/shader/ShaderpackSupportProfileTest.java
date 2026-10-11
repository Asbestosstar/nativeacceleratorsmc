package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.util.Map;

/** Dependency-light checks for semantic shaderpack negotiation. */
public final class ShaderpackSupportProfileTest {
    private ShaderpackSupportProfileTest() {}

    public static void main(String[] args) {
        ShaderpackSupportProfile profile = ShaderpackSupportProfile.builder("legacy-water")
                .requires(ShaderpackCapability.DERIVATIVES,
                        ShaderpackCapability.FRAMEBUFFER_FETCH,
                        ShaderpackCapability.RAY_TRACING)
                .negotiate(Map.of(
                        ShaderpackCapability.DERIVATIVES, ShaderpackCapabilityState.NATIVE,
                        ShaderpackCapability.FRAMEBUFFER_FETCH, ShaderpackCapabilityState.EMULATED));

        expect("legacy-water", profile.name(), "name");
        expect(ShaderpackOutcome.UNSUPPORTED, profile.outcome(), "missing capability is unsupported");
        expect(false, profile.acceptedByDefault(), "unsupported profile is rejected");
        expect(false, profile.accepted(true), "unsupported profile cannot be enabled by approximation");
        expect(ShaderpackOutcome.SEMANTIC_EMULATION,
                profile.result(ShaderpackCapability.FRAMEBUFFER_FETCH).outcome(), "emulation result");
        expect(ShaderpackCapabilityState.UNAVAILABLE,
                profile.result(ShaderpackCapability.RAY_TRACING).state(), "missing result");
        expect(true, profile.diagnostics().stream().anyMatch(value -> value.contains("ray-tracing")),
                "missing capability diagnostic");

        ShaderpackSupportProfile approximate = ShaderpackSupportProfile.builder("stylized")
                .requires(ShaderpackCapability.FP64)
                .negotiate(Map.of(ShaderpackCapability.FP64, ShaderpackCapabilityState.APPROXIMATED));
        expect(ShaderpackOutcome.APPROXIMATION_OPT_IN, approximate.outcome(), "approximation outcome");
        expect(false, approximate.acceptedByDefault(), "approximation requires opt-in");
        expect(true, approximate.accepted(true), "approximation opt-in");

        ShaderpackSupportProfile translated = ShaderpackSupportProfile.builder("basic")
                .requires(ShaderpackCapability.DERIVATIVES)
                .negotiate(Map.of(ShaderpackCapability.DERIVATIVES, ShaderpackCapabilityState.TRANSLATED));
        expect(ShaderpackOutcome.EXACT_TRANSLATION, translated.outcome(), "translation outcome");
        expect(true, translated.acceptedByDefault(), "translation accepted");

        System.out.println("ShaderpackSupportProfileTest: PASS");
    }

    private static void expect(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
        }
    }
}
