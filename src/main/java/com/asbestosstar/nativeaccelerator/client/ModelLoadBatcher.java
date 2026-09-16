package com.asbestosstar.nativeaccelerator.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelDispatcher;
import net.minecraft.client.resources.model.BlockStateDefinitions;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * High-throughput replacements for Minecraft's model and blockstate preparation paths.
 *
 * <p>Pass 1 merely grouped vanilla per-file work into larger tasks. Pass 2 changes the pipeline itself:
 * a persistent work-stealing pool runs a CPU-count number of workers, workers dynamically claim resources
 * to avoid long-tail batches, blockstates use a streaming decoder that bypasses Gson DOM + DataFixer Codec,
 * and ordinary cuboid models use a streaming parser that constructs final model records directly.</p>
 *
 * <p>Every fast parser has a per-resource vanilla retry. A resource pack using an unusual JSON shape can
 * therefore pay the fallback cost without disabling acceleration for the other thousands of files.</p>
 */
public final class ModelLoadBatcher {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter MODEL_LISTER = FileToIdConverter.json("models");
    private static final FileToIdConverter BLOCKSTATE_LISTER = FileToIdConverter.json("blockstates");

    private ModelLoadBatcher() {}

    public static boolean enabled() {
        return com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.booleanValue("model.batchLoading", true);
    }

    public static CompletableFuture<Map<Identifier, UnbakedModel>> loadBlockModels(
            ResourceManager manager, Executor minecraftExecutor) {
        long wallStarted = ModelPipelineProfiler.start();
        Executor workerExecutor = ModelWorkScheduler.executor(minecraftExecutor);
        return CompletableFuture.supplyAsync(() -> {
            long started = ModelPipelineProfiler.start();
            Map<Identifier, Resource> resources = MODEL_LISTER.listMatchingResources(manager);
            ModelPipelineProfiler.end("raw-model.enumerate", started);
            ModelPipelineProfiler.addCount("raw-model.resources", resources.size());
            return new ArrayList<>(resources.entrySet());
        }, workerExecutor).thenCompose(entries -> ModelWorkScheduler.mapIndexed(
                entries.size(), minecraftExecutor, index -> parseModel(entries.get(index)), "raw-model.dynamic"))
                .thenApply(results -> {
                    LinkedHashMap<Identifier, UnbakedModel> models = new LinkedHashMap<>(capacity(results.size()));
                    for (ModelResult result : results) {
                        if (result != null) models.put(result.id(), result.model());
                    }
                    ModelPipelineProfiler.end("raw-model.wall", wallStarted);
                    return Map.copyOf(models);
                });
    }

    private static ModelResult parseModel(Map.Entry<Identifier, Resource> entry) {
        Identifier modelId = MODEL_LISTER.fileToId(entry.getKey());
        Resource resource = entry.getValue();
        long totalStarted = ModelPipelineProfiler.start();
        try {
            CuboidModel model;
            if (com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.booleanValue("model.fastCuboidJson", true)) {
                try (Reader reader = reader(resource)) {
                    model = FastCuboidModelDecoder.parse(reader);
                    ModelPipelineProfiler.addCount("raw-model.fast-hit-count", 1);
                } catch (Exception fastFailure) {
                    long fallbackStarted = ModelPipelineProfiler.start();
                    try (Reader reader = reader(resource)) {
                        model = CuboidModel.fromStream(reader);
                    }
                    ModelPipelineProfiler.end("raw-model.vanilla-fallback", fallbackStarted);
                    ModelPipelineProfiler.addCount("raw-model.fast-fallback-count", 1);
                }
            } else {
                try (Reader reader = reader(resource)) {
                    model = CuboidModel.fromStream(reader);
                }
            }
            return new ModelResult(modelId, model);
        } catch (Exception exception) {
            LOGGER.error("Failed to load model {}", entry.getKey(), exception);
            return null;
        } finally {
            ModelPipelineProfiler.end("raw-model.resource.total", totalStarted);
        }
    }

    public static CompletableFuture<BlockStateModelLoader.LoadedModels> loadBlockStates(
            ResourceManager manager, Executor minecraftExecutor) {
        Function<Identifier, StateDefinition<Block, BlockState>> definitionToBlockState =
                BlockStateDefinitions.definitionLocationToBlockStateMapper();
        long wallStarted = ModelPipelineProfiler.start();
        Executor workerExecutor = ModelWorkScheduler.executor(minecraftExecutor);

        return CompletableFuture.supplyAsync(() -> {
            long started = ModelPipelineProfiler.start();
            Map<Identifier, List<Resource>> resources = BLOCKSTATE_LISTER.listMatchingResourceStacks(manager);
            ModelPipelineProfiler.end("blockstate.enumerate", started);
            ModelPipelineProfiler.addCount("blockstate.resources", resources.size());
            return new ArrayList<>(resources.entrySet());
        }, workerExecutor).thenCompose(entries -> ModelWorkScheduler.mapIndexed(
                entries.size(), minecraftExecutor,
                index -> parseBlockStateStack(entries.get(index), definitionToBlockState),
                "blockstate.dynamic"))
                .thenApply(partialMaps -> {
                    IdentityHashMap<BlockState, BlockStateModel.UnbakedRoot> result = new IdentityHashMap<>();
                    for (IdentityHashMap<BlockState, BlockStateModel.UnbakedRoot> partial : partialMaps) {
                        if (partial != null) result.putAll(partial);
                    }
                    ModelPipelineProfiler.end("blockstate.wall", wallStarted);
                    return new BlockStateModelLoader.LoadedModels(result);
                });
    }

    private static IdentityHashMap<BlockState, BlockStateModel.UnbakedRoot> parseBlockStateStack(
            Map.Entry<Identifier, List<Resource>> entry,
            Function<Identifier, StateDefinition<Block, BlockState>> definitionToBlockState) {
        Identifier stateDefinitionId = BLOCKSTATE_LISTER.fileToId(entry.getKey());
        StateDefinition<Block, BlockState> stateDefinition = definitionToBlockState.apply(stateDefinitionId);
        if (stateDefinition == null) {
            LOGGER.debug("Discovered unknown block state definition {}, ignoring", stateDefinitionId);
            return null;
        }

        long stackStarted = ModelPipelineProfiler.start();
        IdentityHashMap<BlockState, BlockStateModel.UnbakedRoot> result = new IdentityHashMap<>();
        for (Resource resource : entry.getValue()) {
            String source = stateDefinitionId + "/" + resource.sourcePackId();
            try {
                IdentityHashMap<BlockState, BlockStateModel.UnbakedRoot> decoded;
                if (com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.booleanValue("model.fastBlockstateJson", true)) {
                    try (Reader reader = reader(resource)) {
                        decoded = FastBlockStateDecoder.decodeAndInstantiate(reader, stateDefinition, source);
                        ModelPipelineProfiler.addCount("blockstate.fast-hit-count", 1);
                    } catch (Exception fastFailure) {
                        long fallbackStarted = ModelPipelineProfiler.start();
                        decoded = vanillaBlockState(resource, stateDefinitionId, stateDefinition);
                        ModelPipelineProfiler.end("blockstate.vanilla-fallback", fallbackStarted);
                        ModelPipelineProfiler.addCount("blockstate.fast-fallback-count", 1);
                    }
                } else {
                    decoded = vanillaBlockState(resource, stateDefinitionId, stateDefinition);
                }
                // Resource-stack order is significant: later packs override earlier packs exactly as vanilla.
                result.putAll(decoded);
            } catch (Exception exception) {
                LOGGER.error("Failed to load blockstate definition {} from pack {}",
                        stateDefinitionId, resource.sourcePackId(), exception);
            }
        }
        ModelPipelineProfiler.record("blockstate.stack.total", System.nanoTime() - stackStarted,
                entry.getValue().size());
        return result;
    }

    private static IdentityHashMap<BlockState, BlockStateModel.UnbakedRoot> vanillaBlockState(
            Resource resource,
            Identifier stateDefinitionId,
            StateDefinition<Block, BlockState> stateDefinition) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(profiled(resource.open()), StandardCharsets.UTF_8))) {
            long parseStarted = ModelPipelineProfiler.start();
            JsonElement element = StrictJsonParser.parse(reader);
            BlockStateModelDispatcher dispatcher = BlockStateModelDispatcher.CODEC
                    .parse(JsonOps.INSTANCE, element)
                    .getOrThrow(JsonParseException::new);
            ModelPipelineProfiler.end("blockstate.vanilla.json-codec", parseStarted);

            long instantiateStarted = ModelPipelineProfiler.start();
            Map<BlockState, BlockStateModel.UnbakedRoot> instantiated = dispatcher.instantiate(
                    stateDefinition, () -> stateDefinitionId + "/" + resource.sourcePackId());
            ModelPipelineProfiler.record("blockstate.vanilla.dispatcher.instantiate",
                    System.nanoTime() - instantiateStarted, stateDefinition.getPossibleStates().size());
            IdentityHashMap<BlockState, BlockStateModel.UnbakedRoot> result = new IdentityHashMap<>();
            result.putAll(instantiated);
            return result;
        }
    }

    private static InputStream profiled(InputStream input) {
        return com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.booleanValue("model.ioProfiler", false)
                ? new ProfiledResourceInputStream(input)
                : input;
    }

    private static Reader reader(Resource resource) throws Exception {
        long started = ModelPipelineProfiler.start();
        Reader reader = new InputStreamReader(profiled(resource.open()), StandardCharsets.UTF_8);
        ModelPipelineProfiler.end("resource.reader.open", started);
        return reader;
    }

    private static int capacity(int size) {
        if (size < 12) return 16;
        return size >= (1 << 29) ? Integer.MAX_VALUE : (size * 4 / 3) + 1;
    }

    private record ModelResult(Identifier id, UnbakedModel model) {}
}
