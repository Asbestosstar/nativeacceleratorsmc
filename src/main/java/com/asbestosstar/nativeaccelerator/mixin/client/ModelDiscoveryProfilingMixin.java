package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelDagProfiler;
import com.asbestosstar.nativeaccelerator.client.ModelPipelineProfiler;
import net.minecraft.client.resources.model.ModelDiscovery;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(ModelDiscovery.class)
public abstract class ModelDiscoveryProfilingMixin {
    @Unique private long nativeaccelerator$resolveDagStarted;
    @Unique private long nativeaccelerator$resolveCpuStarted;

    @Inject(method = "resolve", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$resolveBegin(CallbackInfoReturnable<Map<Identifier, ResolvedModel>> cir) {
        nativeaccelerator$resolveDagStarted = ModelDagProfiler.begin();
        nativeaccelerator$resolveCpuStarted = ModelPipelineProfiler.start();
    }

    @Inject(method = "resolve", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$resolveEnd(CallbackInfoReturnable<Map<Identifier, ResolvedModel>> cir) {
        ModelDagProfiler.end("model-discovery.resolve", nativeaccelerator$resolveDagStarted);
        if (nativeaccelerator$resolveCpuStarted != 0L) {
            ModelPipelineProfiler.record("model-discovery.resolve.cpu",
                    System.nanoTime() - nativeaccelerator$resolveCpuStarted, cir.getReturnValue().size());
        }
    }
}
