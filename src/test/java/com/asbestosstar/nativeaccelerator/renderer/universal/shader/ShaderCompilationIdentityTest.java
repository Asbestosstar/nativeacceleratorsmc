package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.util.List;

/** Dependency-light checks for shader compilation identity and reflection manifests. */
public final class ShaderCompilationIdentityTest {
    private ShaderCompilationIdentityTest() {}

    public static void main(String[] args) {
        keyIsStableAndSensitiveToEveryDimension();
        keyEncodingDoesNotCollideOnSeparators();
        manifestIsOrderIndependentAndDefensivelyCopied();
        manifestRejectsInvalidInterfaceEntries();
        System.out.println("ShaderCompilationIdentityTest: PASS");
    }

    private static void keyIsStableAndSensitiveToEveryDimension() {
        ShaderCompilationKey base = key();
        check(base.equals(key()), "equal inputs must produce equal keys");
        check(base.hashCode() == key().hashCode(), "equal keys must have equal hash codes");
        check(base.digest().equals(base.cacheNamespace()), "cache namespace aliases digest");
        check(base.digest().length() == 64, "SHA-256 digest must be 64 hex characters");
        String[] values = {"pack-2", "deps-2", "compiler-2", "backend-2", "device-2", "attachments-2", "state-2"};
        for (int i = 0; i < values.length; i++) {
            String[] changed = {"pack", "deps", "compiler", "backend", "device", "attachments", "state"};
            changed[i] = values[i];
            ShaderCompilationKey different = new ShaderCompilationKey(changed[0], changed[1], changed[2],
                    changed[3], changed[4], changed[5], changed[6]);
            check(!base.equals(different), "dimension " + i + " must affect equality");
            check(!base.digest().equals(different.digest()), "dimension " + i + " must affect digest");
        }
    }

    private static void keyEncodingDoesNotCollideOnSeparators() {
        ShaderCompilationKey left = new ShaderCompilationKey("a|2:b", "d", "c", "b", "g", "a", "r");
        ShaderCompilationKey right = new ShaderCompilationKey("a", "2:b|d", "c", "b", "g", "a", "r");
        check(!left.canonicalForm().equals(right.canonicalForm()), "length-prefixed fields must be unambiguous");
        check(!left.digest().equals(right.digest()), "different fields must not share identity digest");
    }

    private static void manifestIsOrderIndependentAndDefensivelyCopied() {
        var first = new ShaderReflectionManifest("fragment", "main",
                List.of(new ShaderReflectionManifest.ResourceBinding(0, 2, "sampled-image", "rgba8", 1, true),
                        new ShaderReflectionManifest.ResourceBinding(0, 1, "uniform-buffer", "mat4", 1, true)),
                List.of(new ShaderReflectionManifest.InterfaceVariable(2, 0, "float", 1, "smooth")),
                List.of(new ShaderReflectionManifest.InterfaceVariable(0, 0, "vec4", 4, "")),
                List.of(new ShaderReflectionManifest.PushConstantRange(0, 64, "mat4")),
                List.of(new ShaderReflectionManifest.SpecializationConstant(7, "int", "3")));
        var second = new ShaderReflectionManifest("fragment", "main",
                List.of(new ShaderReflectionManifest.ResourceBinding(0, 1, "uniform-buffer", "mat4", 1, true),
                        new ShaderReflectionManifest.ResourceBinding(0, 2, "sampled-image", "rgba8", 1, true)),
                first.inputs(), first.outputs(), first.pushConstants(), first.specializationConstants());
        check(first.canonicalForm().equals(second.canonicalForm()), "reflection enumeration order must not affect identity");
        check(first.digest().equals(second.digest()), "equivalent manifests must have equal digests");
        check(first.resources().getClass() != null, "manifest exposes an immutable list");
        try {
            first.resources().add(new ShaderReflectionManifest.ResourceBinding(0, 3, "sampler", "sampler", 1, false));
            throw new AssertionError("manifest resource list must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    private static void manifestRejectsInvalidInterfaceEntries() {
        expectFailure(() -> new ShaderReflectionManifest.ResourceBinding(-1, 0, "buffer", "u32", 1, true));
        expectFailure(() -> new ShaderReflectionManifest.InterfaceVariable(0, 0, "float", 0, ""));
        expectFailure(() -> new ShaderReflectionManifest.PushConstantRange(0, 0, "bytes"));
        expectFailure(() -> new ShaderReflectionManifest.SpecializationConstant(-1, "int", "0"));
    }

    private static ShaderCompilationKey key() {
        return new ShaderCompilationKey("pack", "deps", "compiler", "backend", "device", "attachments", "state");
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("invalid identity component was accepted");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
