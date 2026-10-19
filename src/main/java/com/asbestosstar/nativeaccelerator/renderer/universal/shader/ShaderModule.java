package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.nio.ByteBuffer;
import java.util.List;

public record ShaderModule(
        ShaderSourceSpec source,
        ByteBuffer spirv,
        ShaderReflection reflection,
        List<ShaderDiagnostic> diagnostics) {
    public ShaderModule {
        spirv = spirv == null ? null : spirv.asReadOnlyBuffer();
        diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }

    public ByteBuffer spirvWords() {
        return spirv == null ? null : spirv.asReadOnlyBuffer();
    }

    public boolean isValid() {
        return spirv != null
                && spirv.remaining() > 0
                && diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == ShaderDiagnostic.Severity.ERROR);
    }
}
