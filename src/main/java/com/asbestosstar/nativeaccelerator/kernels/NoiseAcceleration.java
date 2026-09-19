package com.asbestosstar.nativeaccelerator.kernels;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;

/**
 * Minecraft-free eligibility policy for bulk world-generation noise kernels.
 *
 * <p>{@code noise.mode} deliberately exposes the A/B modes used by NativeServer0:</p>
 * <ul>
 *   <li>{@code off}: vanilla Java noise only;</li>
 *   <li>{@code single}: accelerate individual PerlinNoise.addToVolume calls (one staging pair/layer);</li>
 *   <li>{@code stack}: preferred path; stage a DensityBuffer once for an entire Perlin NoiseStack.</li>
 * </ul>
 */
public final class NoiseAcceleration {
    public static final String MIN_CELLS_PROPERTY = "nativeaccelerator.noise.minCells";
    public static final String MODE_PROPERTY = "nativeaccelerator.noise.mode";

    /** 16k floats = 64 KiB; normal 16x384x16 terrain volumes contain 98,304 cells. */
    static final int DEFAULT_MIN_CELLS = 16_384;

    public enum Mode { OFF, SINGLE, STACK }

    private NoiseAcceleration() {}

    public static Mode mode() {
        return switch (NativeAcceleratorConfig.stringValue("noise.mode", "stack").trim().toLowerCase()) {
            case "off", "false", "vanilla", "disabled" -> Mode.OFF;
            case "single", "perlin", "layer" -> Mode.SINGLE;
            default -> Mode.STACK;
        };
    }

    public static boolean singleLayerEnabled() {
        return mode() == Mode.SINGLE;
    }

    public static boolean stackEnabled() {
        return mode() == Mode.STACK;
    }

    public static boolean eligible(int logicalSize, int arrayLength, int sizeX, int sizeY, int sizeZ) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || logicalSize <= 0) return false;
        final long cells;
        try {
            cells = Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
        } catch (ArithmeticException ignored) {
            return false;
        }
        return cells <= logicalSize && cells <= arrayLength && cells >= minimumCells();
    }

    public static boolean addPerlinVolume(float[] values, int logicalSize,
                                          int sizeX, int sizeY, int sizeZ,
                                          int minBlockX, int minBlockY, int minBlockZ,
                                          int stepBlockX, int stepBlockY, int stepBlockZ,
                                          double xzScale, double yScale, float amplitude,
                                          byte[] permutations,
                                          double offsetX, double offsetY, double offsetZ,
                                          boolean wrapCoordinates) {
        if (!singleLayerEnabled() || values == null || permutations == null || permutations.length < 256) return false;
        if (!eligible(logicalSize, values.length, sizeX, sizeY, sizeZ)) return false;

        return NativeKernelBridge.addPerlinVolume(values,
                sizeX, sizeY, sizeZ,
                minBlockX, minBlockY, minBlockZ,
                stepBlockX, stepBlockY, stepBlockZ,
                xzScale, yScale, amplitude,
                permutations,
                offsetX, offsetY, offsetZ,
                wrapCoordinates);
    }

    /** Resolved cell threshold, never negative. Invalid values fall back to the default. */
    static int minimumCells() {
        String raw = NativeAcceleratorConfig.stringValue("noise.minCells", "").trim();
        if (raw.isEmpty()) return DEFAULT_MIN_CELLS;
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException ignored) {
            return DEFAULT_MIN_CELLS;
        }
    }
}
