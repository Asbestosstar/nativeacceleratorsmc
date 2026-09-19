package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelDagProfiler;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.BuiltInBlockModels;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(BuiltInBlockModels.class)
public abstract class BuiltInBlockModelsProfilingMixin {
    @Unique private static final ThreadLocal<Long> nativeaccelerator$started = new ThreadLocal<>();

    @Inject(method = "createBlockModels", at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$begin(BlockColors colors,
            CallbackInfoReturnable<Map<BlockState, BlockModel.Unbaked>> cir) {
        nativeaccelerator$started.set(ModelDagProfiler.begin());
    }

    @Inject(method = "createBlockModels", at = @At("RETURN"), require = 0)
    private static void nativeaccelerator$end(BlockColors colors,
            CallbackInfoReturnable<Map<BlockState, BlockModel.Unbaked>> cir) {
        Long started = nativeaccelerator$started.get();
        nativeaccelerator$started.remove();
        if (started != null) ModelDagProfiler.end("built-in-block-models", started);
    }
}

