package com.asbestosstar.nativeaccelerator.nativeapi;

import java.io.IOException;

public final class PanamaDiagnosticsTest {
    public static void main(String[] args) {
        Throwable failure = new IOException("/Users/example/.cache/native/lib.so", new UnsatisfiedLinkError("missing libdax.so.1"));
        String formatted = PanamaDiagnostics.format("compute backend", PanamaDiagnostics.Stage.LIBRARY_LOOKUP, failure);
        require(formatted.contains("stage=LIBRARY_LOOKUP"), formatted);
        require(formatted.contains("UnsatisfiedLinkError"), formatted);
        require(formatted.contains("<path>"), formatted);
        require(!formatted.contains("/Users/example"), formatted);

        String abi = PanamaDiagnostics.formatAbiMismatch("compute backend", 3, 2);
        require(abi.contains("expectedAbi=3"), abi);
        require(abi.contains("observedAbi=2"), abi);

        System.out.println("PanamaDiagnosticsTest: PASS");
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
