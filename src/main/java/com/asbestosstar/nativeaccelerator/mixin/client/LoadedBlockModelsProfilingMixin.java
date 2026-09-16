package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelDagProfiler;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.LoadedBlockModels;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

@Mixin(LoadedBlockModels.class)
public abstract class LoadedBlockModelsProfilingMixin {
    @Unique private long nativeaccelerator$bakeStarted;

    @Inject(method = "bake", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$bakeBegin(Function<BlockState, BlockStateModel> models, BlockStateModel missing,
            Executor executor, CallbackInfoReturnable<CompletableFuture<Map<BlockState, BlockModel>>> cir) {
        nativeaccelerator$bakeStarted = ModelDagProfiler.begin();
    }

    @Inject(method = "bake", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$bakeReturn(Function<BlockState, BlockStateModel> models, BlockStateModel missing,
            Executor executor, CallbackInfoReturnable<CompletableFuture<Map<BlockState, BlockModel>>> cir) {
        ModelDagProfiler.track("loaded-block-models.bake", cir.getReturnValue(), nativeaccelerator$bakeStarted);
    }
}
