package com.asbestosstar.nativeaccelerator.integration.minecraft263;

import java.util.List;

/**
 * Named 26.3-pre-2 integration targets discovered from the decompiled game source.
 * This class deliberately contains no Minecraft linkage, keeping the universal loader JAR compilable.
 * Concrete Mixins/ASM patches may consume these names directly: the build compiles against the named
 * 26.3 classes, so Mixins are allowed to target them (see the Mixins policy in AGENTS.MD).
 */
public final class Minecraft263AccelerationTargets {
    private Minecraft263AccelerationTargets() {}

    public static final String SIMPLE_BIT_STORAGE = "net.minecraft.util.SimpleBitStorage";
    public static final String DATAFIX_PACKED_BIT_STORAGE = "net.minecraft.util.datafix.PackedBitStorage";
    public static final String MESH_DATA = "com.mojang.blaze3d.vertex.MeshData";
    public static final String NATIVE_IMAGE = "com.mojang.blaze3d.platform.NativeImage";
    public static final String PERLIN_NOISE = "net.minecraft.world.level.levelgen.synth.PerlinNoise";
    public static final String NOISE_STACK = "net.minecraft.world.level.levelgen.synth.NoiseStack";
    public static final String DENSITY_BUFFER = "net.minecraft.world.level.levelgen.densityfunction.DensityBuffer";
    public static final String DENSITY_VOLUME = "net.minecraft.world.level.levelgen.densityfunction.DensityVolume";

    /** Base class shared by the dedicated server and the client's single-player integrated server. */
    public static final String MINECRAFT_SERVER = "net.minecraft.server.MinecraftServer";
    /** Single-player server hosted inside the client. Only present on a client. */
    public static final String INTEGRATED_SERVER = "net.minecraft.client.server.IntegratedServer";
    /** Standalone server. Only present when no client is running. */
    public static final String DEDICATED_SERVER = "net.minecraft.server.dedicated.DedicatedServer";

    public static final List<Target> FIRST_WAVE = List.of(
            new Target(SIMPLE_BIT_STORAGE, "unpack([I)V", "simple packed-bit unpack"),
            new Target(DATAFIX_PACKED_BIT_STORAGE, "get(I)I / set(II)V", "dense packed-bit/DataFixer repack batches"),
            new Target(MESH_DATA, "decodeQuadCentroids + SortState", "centroid generation and distance index sort"),
            new Target(NATIVE_IMAGE, "fillRect / copyRect / bulk channel conversion", "native pixel kernels"),
            new Target(PERLIN_NOISE, "addToVolume", "bulk regular-grid Perlin evaluation")
    );

    /**
     * Startup-timing seams: read-only observation of when the server becomes usable. Unlike
     * {@link #FIRST_WAVE} these dispatch into no native kernel, so they cannot change behaviour; they only
     * record durations through {@code com.asbestosstar.nativeaccelerator.startup.StartupTimer}.
     *
     * <p>The environment of each seam is decided by the Mixin's package, per the gating convention in
     * AGENTS.MD, which is why the ready milestone is not in the shared class:</p>
     *
     * <ul>
     *   <li>{@code MinecraftServerTimingMixin} (common) times {@code runServer()} and the
     *       {@code initServer()} call inside it. {@code initServer} is abstract on the base class and
     *       implemented by both {@code DedicatedServer} and {@code IntegratedServer}, so timing the call
     *       site covers both without a Mixin per subclass.</li>
     *   <li>{@code DedicatedServerReadyMixin} (server) times the {@code isReady = true} write. That single
     *       write is in the shared {@code MinecraftServer#runServer()}, so this seam is gated to a
     *       dedicated server to avoid reporting a single-player world load as a dedicated-server event.</li>
     *   <li>{@code IntegratedServerMixin} (client) times {@code IntegratedServer#initServer()}, the
     *       single-player world load, and is the client counterpart of the dedicated ready milestone.</li>
     * </ul>
     */
    public static final List<Target> STARTUP_TIMING = List.of(
            new Target(MINECRAFT_SERVER, "runServer()V / initServer()Z call site",
                    "server.run-loop and server.init durations (shared by both server flavours)"),
            new Target(DEDICATED_SERVER, "MinecraftServer.isReady = true (in runServer)",
                    "server.ready milestone; writes the report once a dedicated server accepts players"),
            new Target(INTEGRATED_SERVER, "initServer()Z",
                    "client.integrated-server-init duration for a single-player world load")
    );

    public record Target(String className, String methods, String nativeKernel) {}
}
