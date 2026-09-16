package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelPipelineProfiler;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.Reader;

/** Measures vanilla CuboidModel Gson parsing, principally fast-parser fallback work. */
@Mixin(CuboidModel.class)
public abstract class CuboidModelParseProfilingMixin {
    @Unique private static final ThreadLocal<Long> nativeaccelerator$parseStart = new ThreadLocal<>();

    @Inject(method = "fromStream", at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$begin(Reader reader, CallbackInfoReturnable<CuboidModel> cir) {
        nativeaccelerator$parseStart.set(ModelPipelineProfiler.start());
    }

    @Inject(method = "fromStream", at = @At("RETURN"), require = 0)
    private static void nativeaccelerator$end(Reader reader, CallbackInfoReturnable<CuboidModel> cir) {
        Long started = nativeaccelerator$parseStart.get();
        nativeaccelerator$parseStart.remove();
        if (started != null && started != 0L) {
            ModelPipelineProfiler.record("raw-model.vanilla.cuboid-from-stream", System.nanoTime() - started);
        }
    }
}
