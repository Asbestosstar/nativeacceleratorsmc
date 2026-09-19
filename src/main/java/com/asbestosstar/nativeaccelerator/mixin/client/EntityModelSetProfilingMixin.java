package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelDagProfiler;
import net.minecraft.client.model.geom.EntityModelSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityModelSet.class)
public abstract class EntityModelSetProfilingMixin {
    @Unique private static final ThreadLocal<Long> nativeaccelerator$started = new ThreadLocal<>();

    @Inject(method = "vanilla", at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$begin(CallbackInfoReturnable<EntityModelSet> cir) {
        nativeaccelerator$started.set(ModelDagProfiler.begin());
    }

    @Inject(method = "vanilla", at = @At("RETURN"), require = 0)
    private static void nativeaccelerator$end(CallbackInfoReturnable<EntityModelSet> cir) {
        Long started = nativeaccelerator$started.get();
        nativeaccelerator$started.remove();
        if (started != null) ModelDagProfiler.end("entity-model-set", started);
    }
}

