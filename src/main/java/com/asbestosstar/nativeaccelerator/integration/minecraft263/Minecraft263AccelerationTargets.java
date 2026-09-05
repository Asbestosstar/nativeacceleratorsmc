package com.asbestosstar.nativeaccelerator.integration.minecraft263;

import java.util.List;

/**
 * Named 26.3-pre-2 integration targets discovered from the decompiled game source.
 * This class deliberately contains no Minecraft linkage, keeping the universal loader JAR compilable.
 * Concrete Mixins/ASM patches can consume these names once the mapping/remap layer is finalized.
 */
public final class Minecraft263AccelerationTargets {
    private Minecraft263AccelerationTargets() {}

    public static final String SIMPLE_BIT_STORAGE = "net.minecraft.util.SimpleBitStorage";
    public static final String DATAFIX_PACKED_BIT_STORAGE = "net.minecraft.util.datafix.fixes.PackedBitStorage";
    public static final String MESH_DATA = "com.mojang.blaze3d.vertex.MeshData";
    public static final String NATIVE_IMAGE = "com.mojang.blaze3d.platform.NativeImage";
    public static final String PERLIN_NOISE = "net.minecraft.world.level.levelgen.synth.PerlinNoise";
    public static final String NOISE_STACK = "net.minecraft.world.level.levelgen.synth.NoiseStack";
    public static final String DENSITY_BUFFER = "net.minecraft.world.level.levelgen.densityfunction.DensityBuffer";
    public static final String DENSITY_VOLUME = "net.minecraft.world.level.levelgen.densityfunction.DensityVolume";

    public static final List<Target> FIRST_WAVE = List.of(
            new Target(SIMPLE_BIT_STORAGE, "unpack([I)V", "simple packed-bit unpack"),
            new Target(DATAFIX_PACKED_BIT_STORAGE, "get(I)I / set(II)V", "dense packed-bit/DataFixer repack batches"),
            new Target(MESH_DATA, "decodeQuadCentroids + SortState", "centroid generation and distance index sort"),
            new Target(NATIVE_IMAGE, "fillRect / copyRect / bulk channel conversion", "native pixel kernels"),
            new Target(PERLIN_NOISE, "addToVolume", "bulk regular-grid Perlin evaluation")
    );

    public record Target(String className, String methods, String nativeKernel) {}
}
