package com.asbestosstar.nativeaccelerator.renderer.universal;

import java.nio.ByteBuffer;

public final class UrdPixelValidationTest {
    public static void main(String[] args) {
        ByteBuffer pixels = ByteBuffer.allocate(4 * 4 * 4);
        int position = pixels.position();
        UrdPixelValidation.validateRgba8Readback(pixels, 4, 4);
        if (pixels.position() != position) throw new AssertionError("readback validator changed position");
        expectFailure(() -> UrdPixelValidation.validateRgba8Readback(ByteBuffer.allocate(3), 1, 1));
        expectFailure(() -> UrdPixelValidation.validateRgba8Readback(ByteBuffer.allocate(4), 0, 1));
        System.out.println("UrdPixelValidationTest: PASS");
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("accepted invalid readback");
        } catch (IllegalArgumentException | IllegalStateException expected) {
        }
    }
}
