package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelDagProfiler;
import com.asbestosstar.nativeaccelerator.client.ModelPipelineProfiler;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.block.LoadedBlockModels;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ClientItemInfoLoader;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Fine-grained wall-clock probes for the remaining ModelManager DAG nodes. */
@Mixin(ModelManager.class)
public abstract class ModelManagerDAGProfilingMixin {
    @Unique private static final ThreadLocal<Long> nativeaccelerator$discoveryStarted = new ThreadLocal<>();
    @Unique private static final ThreadLocal<Long> nativeaccelerator$groupsStarted = new ThreadLocal<>();
    @Unique private static final ThreadLocal<Long> nativeaccelerator$loadModelsStarted = new ThreadLocal<>();
    @Unique private static final ThreadLocal<Long> nativeaccelerator$dispatchStarted = new ThreadLocal<>();

    @Inject(method = "discoverModelDependencies", at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$discoveryBegin(Map<Identifier, UnbakedModel> models,
            BlockStateModelLoader.LoadedModels blockstates, ClientItemInfoLoader.LoadedClientInfos items,
            CallbackInfoReturnable<?> cir) {
        nativeaccelerator$discoveryStarted.set(ModelDagProfiler.begin());
    }

    @Inject(method = "discoverModelDependencies", at = @At("RETURN"), require = 0)
    private static void nativeaccelerator$discoveryEnd(Map<Identifier, UnbakedModel> models,
            BlockStateModelLoader.LoadedModels blockstates, ClientItemInfoLoader.LoadedClientInfos items,
            CallbackInfoReturnable<?> cir) {
        Long started = nativeaccelerator$discoveryStarted.get();
        nativeaccelerator$discoveryStarted.remove();
        if (started != null) {
            ModelDagProfiler.end("model-discovery.total", started);
            ModelPipelineProfiler.record("model-discovery.total.cpu", System.nanoTime() - started, models.size());
        }
    }

    @Inject(method = "buildModelGroups", at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$groupsBegin(BlockColors colors, BlockStateModelLoader.LoadedModels models,
            CallbackInfoReturnable<Object2IntMap<BlockState>> cir) {
        nativeaccelerator$groupsStarted.set(ModelDagProfiler.begin());
    }

    @Inject(method = "buildModelGroups", at = @At("RETURN"), require = 0)
    private static void nativeaccelerator$groupsEnd(BlockColors colors, BlockStateModelLoader.LoadedModels models,
            CallbackInfoReturnable<Object2IntMap<BlockState>> cir) {
        Long started = nativeaccelerator$groupsStarted.get();
        nativeaccelerator$groupsStarted.remove();
        if (started != null) ModelDagProfiler.end("model-groups", started);
    }

    @Inject(method = "loadModels", at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$loadModelsBegin(SpriteLoader.Preparations blockAtlas,
            SpriteLoader.Preparations itemAtlas, ModelBakery bakery, LoadedBlockModels blockModels,
            Object2IntMap<BlockState> modelGroups, EntityModelSet entityModelSet, Executor executor,
            CallbackInfoReturnable<CompletableFuture<?>> cir) {
        nativeaccelerator$loadModelsStarted.set(ModelDagProfiler.begin());
    }

    @Inject(method = "loadModels", at = @At("RETURN"), require = 0)
    private static void nativeaccelerator$loadModelsEnd(SpriteLoader.Preparations blockAtlas,
            SpriteLoader.Preparations itemAtlas, ModelBakery bakery, LoadedBlockModels blockModels,
            Object2IntMap<BlockState> modelGroups, EntityModelSet entityModelSet, Executor executor,
            CallbackInfoReturnable<CompletableFuture<?>> cir) {
        Long started = nativeaccelerator$loadModelsStarted.get();
        nativeaccelerator$loadModelsStarted.remove();
        if (started != null) ModelDagProfiler.track("model-load.final", cir.getReturnValue(), started);
    }

    @Inject(method = "createBlockStateToModelDispatch", at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$dispatchBegin(Map<BlockState, BlockStateModel> baked,
            BlockStateModel missing, CallbackInfoReturnable<Map<BlockState, BlockStateModel>> cir) {
        nativeaccelerator$dispatchStarted.set(ModelDagProfiler.begin());
    }

    @Inject(method = "createBlockStateToModelDispatch", at = @At("RETURN"), require = 0)
    private static void nativeaccelerator$dispatchEnd(Map<BlockState, BlockStateModel> baked,
            BlockStateModel missing, CallbackInfoReturnable<Map<BlockState, BlockStateModel>> cir) {
        Long started = nativeaccelerator$dispatchStarted.get();
        nativeaccelerator$dispatchStarted.remove();
        if (started != null) ModelDagProfiler.end("blockstate-dispatch.final", started);
    }
}
