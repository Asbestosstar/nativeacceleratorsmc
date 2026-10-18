package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

public record ShaderDiagnostic(Severity severity, String message, String sourceName, int line, int column) {
    public enum Severity { ERROR, WARNING, INFO }

    public ShaderDiagnostic {
        severity = severity == null ? Severity.ERROR : severity;
        message = message == null ? "shader compilation failed" : message;
        sourceName = sourceName == null ? "<unknown>" : sourceName;
        line = Math.max(0, line);
        column = Math.max(0, column);
    }
}
