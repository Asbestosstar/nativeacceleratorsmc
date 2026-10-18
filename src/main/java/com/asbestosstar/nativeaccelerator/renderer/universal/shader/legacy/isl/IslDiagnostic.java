package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.isl;

public record IslDiagnostic(Severity severity, String message, int line, int column) {
    public enum Severity { ERROR, WARNING, INFO }

    public IslDiagnostic {
        severity = severity == null ? Severity.ERROR : severity;
        message = message == null ? "ISL diagnostic" : message;
        line = Math.max(1, line);
        column = Math.max(1, column);
    }
}
