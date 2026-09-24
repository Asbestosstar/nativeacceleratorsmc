package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.FastModelGroupCollector;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ModelGroupCollector;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ModelGroupCollector.class)
public abstract class ModelGroupCollectorFastMixin {
    @org.spongepowered.asm.mixin.Unique private static final boolean nativeaccelerator$FAST_GROUPS =
            com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.booleanValue("model.fastGroups", true);
    @Inject(method = "build", at = @At("HEAD"), cancellable = true, require = 0)
    private static void nativeaccelerator$fastGroups(BlockColors colors, BlockStateModelLoader.LoadedModels input,
            CallbackInfoReturnable<Object2IntMap<BlockState>> cir) {
        if (nativeaccelerator$FAST_GROUPS) {
            cir.setReturnValue(FastModelGroupCollector.build(colors, input));
        }
    }
}

