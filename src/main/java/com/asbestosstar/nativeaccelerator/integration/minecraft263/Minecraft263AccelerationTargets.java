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

    public static final String MODEL_MANAGER = "net.minecraft.client.resources.model.ModelManager";
    public static final String BLOCKSTATE_MODEL_LOADER = "net.minecraft.client.resources.model.BlockStateModelLoader";
    public static final String BLOCKSTATE_MODEL_DISPATCHER = "net.minecraft.client.renderer.block.dispatch.BlockStateModelDispatcher";
    public static final String CUBOID_MODEL = "net.minecraft.client.resources.model.cuboid.CuboidModel";
    public static final String FILE_TO_ID_CONVERTER = "net.minecraft.resources.FileToIdConverter";
    public static final String CLIENT_ITEM_INFO_LOADER = "net.minecraft.client.resources.model.ClientItemInfoLoader";
    public static final String SPRITE_LOADER = "net.minecraft.client.renderer.texture.SpriteLoader";
    public static final String STITCHER = "net.minecraft.client.renderer.texture.Stitcher";
    public static final String SPRITE_RESOURCE_LOADER = "net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader";
    public static final String FILE_PACK_RESOURCES = "net.minecraft.server.packs.FilePackResources";
    public static final String MODEL_DISCOVERY = "net.minecraft.client.resources.model.ModelDiscovery";
    public static final String MODEL_GROUP_COLLECTOR = "net.minecraft.client.resources.model.ModelGroupCollector";

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
     *   <li>{@code MinecraftServerTimingMixin} (common) times {@code runServer()}, the
     *       {@code initServer()} call inside it, and splits fresh-world startup into
     *       {@code setInitialSpawn(...)} versus {@code prepareLevels()} so global-spawn search and
     *       initial-chunk readiness can be measured independently.</li>
     *   <li>{@code DedicatedServerReadyMixin} (server) observes the {@code isReady = true} write. The write
     *       occurs every run-loop iteration, so the Mixin has its own one-shot guard and never routes
     *       steady-state ticks through synchronized startup-timer bookkeeping.</li>
     *   <li>{@code IntegratedServerMixin} (client) times {@code IntegratedServer#initServer()}, the
     *       single-player world load, and is the client counterpart of the dedicated ready milestone.</li>
     * </ul>
     */
    public static final List<Target> STARTUP_TIMING = List.of(
            new Target(MINECRAFT_SERVER, "runServer()V / initServer()Z / setInitialSpawn(...) / prepareLevels()",
                    "server.run-loop, server.init, global-spawn, and initial-chunk durations"),
            new Target(DEDICATED_SERVER, "MinecraftServer.isReady = true (in runServer)",
                    "server.ready milestone; writes the report once a dedicated server accepts players"),
            new Target(INTEGRATED_SERVER, "initServer()Z",
                    "client.integrated-server-init duration for a single-player world load")
    );

    /**
     * Client model-reload seams replaced/profiled by the high-throughput model pipeline.  The fast JSON
     * decoders remain Java-side because their inputs are tiny, branch-heavy documents where a Panama
     * transition per file would cost more than it saves; native offload remains available for later bulk
     * binary stages once profiling shows an appropriate crossover.
     */
    public static final List<Target> MODEL_RELOAD = List.of(
            new Target(MODEL_MANAGER, "loadBlockModels(ResourceManager,Executor)",
                    "dynamic CPU work queue + streaming CuboidModel decode with vanilla fallback"),
            new Target(BLOCKSTATE_MODEL_LOADER, "loadBlockStates(ResourceManager,Executor)",
                    "dynamic CPU work queue + direct blockstate compile with vanilla fallback"),
            new Target(BLOCKSTATE_MODEL_DISPATCHER, "instantiate(StateDefinition,Supplier)",
                    "bypassed by fast blockstate compiler; retained/profiling for fallback"),
            new Target(CUBOID_MODEL, "fromStream(Reader)",
                    "vanilla fallback and profiler seam"),
            new Target(FILE_TO_ID_CONVERTER, "listMatchingResources / listMatchingResourceStacks",
                    "reload-scoped authoritative enumeration reuse without first-touch map copying + profiler seam"),
            new Target(CLIENT_ITEM_INFO_LOADER, "scheduleLoad(ResourceManager,Executor)",
                    "shared work-stealing item loader + streaming decoder (including common tint payloads) with codec fallback"),
            new Target(SPRITE_LOADER, "runSpriteSuppliers / loadAndStitch / stitch",
                    "bounded low-priority sprite-source/mipmap scheduler + live placement-compatible FastStitcher + warm non-GUI layout cache + DAG profiler"),
            new Target(STITCHER, "registerSprite / stitch / gatherSprites",
                    "FastStitcher preserves vanilla placement decisions while pruning impossible fragmented subtrees"),
            new Target(SPRITE_RESOURCE_LOADER, "create(Set) invoked by SpriteLoader#loadAndStitch",
                    "metadata-preserving atlas loader that restores cached RGBA on hits and falls back to vanilla STB decode"),
            new Target(NATIVE_IMAGE, "pixels field (Accessor)",
                    "bulk mapped RGBA restore plus bounded staging copy; persistent writes deferred until after startup"),
            new Target(FILE_PACK_RESOURCES, "listResources / getNamespaces",
                    "optional per-ZipFile prefix index (off by default after cold-start profiling); source-byte cache also off by default"),
            new Target(MODEL_DISCOVERY, "resolve / getOrCreateModel",
                    "parent-first validity propagation order + fast cache-hit path + DAG profiler"),
            new Target(MODEL_GROUP_COLLECTOR, "build",
                    "allocation-reduced grouping with vanilla-compatible group semantics")
    );

    public record Target(String className, String methods, String nativeKernel) {}
}

