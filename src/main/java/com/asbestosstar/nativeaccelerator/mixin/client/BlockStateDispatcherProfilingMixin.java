package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelPipelineProfiler;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelDispatcher;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Supplier;

/** Aggregate profiling for dispatcher expansion across every blockstate definition. */
@Mixin(BlockStateModelDispatcher.class)
public abstract class BlockStateDispatcherProfilingMixin {
    @Unique private static final ThreadLocal<Long> nativeaccelerator$started = new ThreadLocal<>();

    @Inject(method = "instantiate", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$instantiateBegin(
            StateDefinition<Block, BlockState> stateDefinition,
            Supplier<String> source,
            CallbackInfoReturnable<Map<BlockState, BlockStateModel.UnbakedRoot>> cir) {
        nativeaccelerator$started.set(ModelPipelineProfiler.start());
    }

    @Inject(method = "instantiate", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$instantiateEnd(
            StateDefinition<Block, BlockState> stateDefinition,
            Supplier<String> source,
            CallbackInfoReturnable<Map<BlockState, BlockStateModel.UnbakedRoot>> cir) {
        Long started = nativeaccelerator$started.get();
        nativeaccelerator$started.remove();
        if (started != null && started != 0L) {
            ModelPipelineProfiler.record("dispatcher.instantiate.total", System.nanoTime() - started,
                    stateDefinition.getPossibleStates().size());
        }
    }
}

