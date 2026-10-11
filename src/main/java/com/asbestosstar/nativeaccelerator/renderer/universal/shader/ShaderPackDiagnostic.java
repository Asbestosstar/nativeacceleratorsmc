package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

/** Stable, dependency-free diagnostic for rejected or unavailable shader sources. */
public record ShaderPackDiagnostic(Kind kind, String path, String detail) {
    public enum Kind { INVALID_PATH, NOT_FOUND, DIRECTORY, TOO_LARGE, LIMIT_EXCEEDED, IO_ERROR, INCLUDE_ERROR }

    public ShaderPackDiagnostic {
        kind = kind == null ? Kind.IO_ERROR : kind;
        path = path == null ? "" : path;
        detail = detail == null ? "" : detail;
    }

    @Override
    public String toString() {
        return kind + (path.isEmpty() ? "" : " [" + path + "]") + (detail.isEmpty() ? "" : ": " + detail);
    }
}
