package com.asbestosstar.nativeaccelerator.nativeapi;


/** Bounded diagnostics for Java FFM/native-library bootstrap failures. */
public final class PanamaDiagnostics {
    public enum Stage {
        RESOURCE_EXTRACTION,
        ARENA_CREATION,
        LIBRARY_LOOKUP,
        LINKER_INITIALIZATION,
        SYMBOL_BINDING,
        ABI_PROBE,
        ABI_VALIDATION,
        UNKNOWN
    }

    private static final int MAX_CAUSES = 8;
    private static final int MAX_MESSAGE = 320;

    private PanamaDiagnostics() {}

    public static String format(String subsystem, Stage stage, Throwable failure) {
        String name = subsystem == null || subsystem.isBlank() ? "native backend" : subsystem.trim();
        String safeStage = stage == null ? Stage.UNKNOWN.name() : stage.name();
        StringBuilder out = new StringBuilder("[Native Accelerator] Panama ")
                .append(name).append(" failed: stage=").append(safeStage)
                .append(" causes=");
        Throwable current = failure;
        int count = 0;
        while (current != null && count++ < MAX_CAUSES) {
            if (count > 1) out.append(" -> ");
            out.append(current.getClass().getName()).append(':').append(sanitize(current.getMessage()));
            current = current.getCause();
        }
        if (current != null) out.append(" -> <cause-chain-truncated>");
        if (failure == null) out.append("<none>");
        return out.toString();
    }

    public static String formatAbiMismatch(String subsystem, int expected, int observed) {
        String name = subsystem == null || subsystem.isBlank() ? "native backend" : subsystem.trim();
        return "[Native Accelerator] Panama " + name + " failed: stage=ABI_VALIDATION"
                + " expectedAbi=" + expected + " observedAbi=" + observed;
    }

    static String sanitize(String message) {
        if (message == null || message.isBlank()) return "<none>";
        String value = message.replace('\r', ' ').replace('\n', ' ').trim();
        value = value.replaceAll("(?i)([A-Z]:)?[/\\\\][^ ]+", "<path>");
        if (value.length() > MAX_MESSAGE) value = value.substring(0, MAX_MESSAGE) + "...";
        return value;
    }

    /** Mutable only during one bootstrap attempt; it is never shared across threads. */
    public static final class LoadStage {
        private Stage value = Stage.UNKNOWN;
        public void set(Stage value) { this.value = value == null ? Stage.UNKNOWN : value; }
        public Stage value() { return value; }
    }
}
