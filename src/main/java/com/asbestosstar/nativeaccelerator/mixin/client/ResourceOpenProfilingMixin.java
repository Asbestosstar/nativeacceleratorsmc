package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelPipelineProfiler;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;

/** Measures resource-stream creation cost without wrapping or changing the returned stream. */
@Mixin(Resource.class)
public abstract class ResourceOpenProfilingMixin {
    @Unique private static final ThreadLocal<Long> nativeaccelerator$openStarted = new ThreadLocal<>();

    @Inject(method = "open", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$openBegin(CallbackInfoReturnable<InputStream> cir) {
        nativeaccelerator$openStarted.set(ModelPipelineProfiler.start());
    }

    @Inject(method = "open", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$openEnd(CallbackInfoReturnable<InputStream> cir) {
        Long started = nativeaccelerator$openStarted.get();
        nativeaccelerator$openStarted.remove();
        if (started != null && started != 0L) {
            ModelPipelineProfiler.record("resource.open", System.nanoTime() - started);
        }
    }
}
