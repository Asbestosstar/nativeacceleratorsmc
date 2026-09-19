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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Separates variant-selector expansion from total dispatcher instantiation cost. */
@Mixin(BlockStateModelDispatcher.SimpleModelSelectors.class)
public abstract class BlockStateSimpleSelectorsProfilingMixin {
    @Unique private static final ThreadLocal<Long> nativeaccelerator$started = new ThreadLocal<>();

    @Inject(method = "instantiate", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$begin(
            StateDefinition<Block, BlockState> stateDefinition,
            Supplier<String> source,
            BiConsumer<BlockState, BlockStateModel.UnbakedRoot> output,
            CallbackInfo ci) {
        nativeaccelerator$started.set(ModelPipelineProfiler.start());
    }

    @Inject(method = "instantiate", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$end(
            StateDefinition<Block, BlockState> stateDefinition,
            Supplier<String> source,
            BiConsumer<BlockState, BlockStateModel.UnbakedRoot> output,
            CallbackInfo ci) {
        Long started = nativeaccelerator$started.get();
        nativeaccelerator$started.remove();
        if (started != null && started != 0L) {
            ModelPipelineProfiler.record("dispatcher.variants", System.nanoTime() - started,
                    stateDefinition.getPossibleStates().size());
        }
    }
}
