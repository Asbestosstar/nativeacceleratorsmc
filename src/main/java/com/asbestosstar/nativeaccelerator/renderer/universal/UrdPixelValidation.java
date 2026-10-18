package com.asbestosstar.nativeaccelerator.renderer.universal;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Pixel-level assertions for the deterministic RGBA8 URD triangle scene. */
public final class UrdPixelValidation {
    private UrdPixelValidation() {
    }

    public static void validateRgba8Readback(ByteBuffer pixels, int width, int height) {
        if (pixels == null) throw new IllegalArgumentException("null readback");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("invalid readback dimensions");
        long expected;
        try {
            expected = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("readback dimensions overflow RGBA8 size", ex);
        }
        if (expected > Integer.MAX_VALUE || pixels.remaining() != (int) expected) {
            throw new IllegalStateException("RGBA8 readback size mismatch: expected " + expected
                    + ", got " + pixels.remaining());
        }
    }

    public static Result validateTriangle(ByteBuffer pixels, int width, int height) {
        validateRgba8Readback(pixels, width, height);
        ByteBuffer value = pixels.duplicate();
        requireBackground(value, width, height, 0, 0);
        requireBackground(value, width, height, width - 1, height - 1);
        int centerX = width / 2;
        int centerY = height / 2;
        int center = offset(centerX, centerY, width);
        int red = value.get(center) & 0xff;
        int green = value.get(center + 1) & 0xff;
        int blue = value.get(center + 2) & 0xff;
        int alpha = value.get(center + 3) & 0xff;
        if (red < 32 || green < 32 || blue < 32 || alpha < 200) {
            throw new IllegalStateException("triangle center pixel is not the expected interpolated color: rgba="
                    + red + "," + green + "," + blue + "," + alpha);
        }
        return new Result(width, height, sha256(value), centerX, centerY, red, green, blue, alpha);
    }

    private static void requireBackground(ByteBuffer pixels, int width, int height, int x, int y) {
        int at = offset(x, y, width);
        if ((pixels.get(at) & 0xff) > 8 || (pixels.get(at + 1) & 0xff) > 8
                || (pixels.get(at + 2) & 0xff) > 8) {
            throw new IllegalStateException("expected cleared background at " + x + "," + y);
        }
    }

    private static int offset(int x, int y, int width) {
        return Math.multiplyExact(Math.addExact(Math.multiplyExact(y, width), x), 4);
    }

    private static String sha256(ByteBuffer pixels) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer value = pixels.duplicate();
            byte[] block = new byte[Math.min(8192, Math.max(1, value.remaining()))];
            while (value.hasRemaining()) {
                int count = Math.min(block.length, value.remaining());
                value.get(block, 0, count);
                digest.update(block, 0, count);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest.digest()) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    public record Result(int width, int height, String readbackHash, int centerX, int centerY,
                         int red, int green, int blue, int alpha) {
    }
}
