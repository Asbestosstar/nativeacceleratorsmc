package com.asbestosstar.nativeaccelerator.renderer.metal;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Plain-main coverage for the default-off routine Metal logging switch. */
public final class MetalLoggingTest {
    private static final String PROPERTY = "nativeaccelerator.renderer.metal.logging";

    public static void main(String[] args) {
        String original = System.getProperty(PROPERTY);
        try {
            System.clearProperty(PROPERTY);
            require(!MetalLogging.enabled(), "logging must default to disabled");
            require(outputFor("default-off").isEmpty(), "default-off logging must not print");

            System.setProperty(PROPERTY, "true");
            require(MetalLogging.enabled(), "explicit true must enable logging");
            String enabledOutput = outputFor("enabled-message");
            require(enabledOutput.equals("[Native Accelerator] enabled-message" + System.lineSeparator()),
                    "enabled logging must print the message with its standard prefix");

            System.setProperty(PROPERTY, "false");
            require(!MetalLogging.enabled(), "explicit false must disable logging");
            require(outputFor("disabled-message").isEmpty(), "explicit false must suppress output");

            System.setProperty(PROPERTY, "yes");
            require(!MetalLogging.enabled(), "non-true values must remain disabled");
            require(outputFor("invalid-message").isEmpty(), "non-true values must suppress output");
            System.out.println("MetalLoggingTest: PASS");
        } finally {
            if (original == null) System.clearProperty(PROPERTY);
            else System.setProperty(PROPERTY, original);
        }
    }

    private static String outputFor(String message) {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            MetalLogging.info(message);
        } finally {
            System.setOut(original);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
