package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.isl;

import java.util.List;

/** Parsed ISL source plus diagnostics; no GLSL/SPIR-V equivalence is implied. */
public record IslModule(
        IslProfile profile,
        String sourceName,
        List<IslToken> tokens,
        List<IslDiagnostic> diagnostics) {
    public IslModule {
        profile = profile == null ? IslProfile.ISL_3_0_STRICT : profile;
        sourceName = sourceName == null ? "<unknown>" : sourceName;
        tokens = List.copyOf(tokens == null ? List.of() : tokens);
        diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }

    public boolean isValid() {
        return diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == IslDiagnostic.Severity.ERROR);
    }
}
