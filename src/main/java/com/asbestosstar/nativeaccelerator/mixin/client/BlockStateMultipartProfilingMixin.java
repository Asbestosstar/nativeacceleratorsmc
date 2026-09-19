package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelPipelineProfiler;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelDispatcher;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Separates multipart predicate/model construction from total dispatcher instantiation cost. */
@Mixin(BlockStateModelDispatcher.MultiPartDefinition.class)
public abstract class BlockStateMultipartProfilingMixin {
    @Unique private static final ThreadLocal<Long> nativeaccelerator$started = new ThreadLocal<>();

    @Inject(method = "instantiate", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$begin(
            StateDefinition<Block, BlockState> stateDefinition,
            CallbackInfoReturnable<MultiPartModel.Unbaked> cir) {
        nativeaccelerator$started.set(ModelPipelineProfiler.start());
    }

    @Inject(method = "instantiate", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$end(
            StateDefinition<Block, BlockState> stateDefinition,
            CallbackInfoReturnable<MultiPartModel.Unbaked> cir) {
        Long started = nativeaccelerator$started.get();
        nativeaccelerator$started.remove();
        if (started != null && started != 0L) {
            ModelPipelineProfiler.record("dispatcher.multipart-build", System.nanoTime() - started,
                    stateDefinition.getPossibleStates().size());
        }
    }
}
