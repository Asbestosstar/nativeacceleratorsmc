package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.isl;

import java.util.ArrayList;

/**
 * Clean-room ISL entry point. Lexing is available now; parsing and appearance/runtime semantics are
 * deliberately reported as unsupported until their specification-backed implementations exist.
 */
public final class IslFrontend {
    public IslModule compile(String sourceName, String source, IslProfile profile) {
        IslLexer.Result lexed = new IslLexer().lex(source);
        var diagnostics = new ArrayList<>(lexed.diagnostics());
        if (profile != IslProfile.ISL_3_0_STRICT) {
            diagnostics.add(new IslDiagnostic(IslDiagnostic.Severity.ERROR,
                    "unsupported ISL profile: " + profile, 1, 1));
        }
        diagnostics.add(new IslDiagnostic(IslDiagnostic.Severity.ERROR,
                "ISL parser/typed appearance runtime is not implemented; no GLSL or SPIR-V fallback is claimed",
                1, 1));
        return new IslModule(profile, sourceName, lexed.tokens(), diagnostics);
    }
}
