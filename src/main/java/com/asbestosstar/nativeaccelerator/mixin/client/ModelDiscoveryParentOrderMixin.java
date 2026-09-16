package com.asbestosstar.nativeaccelerator.mixin.client;

import net.minecraft.client.resources.model.ModelDiscovery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Collections;
import java.util.List;

/**
 * Reorders ModelDiscovery's unresolved parent list into parent-first order before vanilla validity
 * propagation. Discovery enqueues a parent only after visiting its child, so the vanilla list is naturally
 * child-first; its repeated scan may therefore need one full pass per inheritance depth. Reversing the
 * list keeps the exact vanilla propagation algorithm/result but lets ordinary parent chains collapse in a
 * single pass. Cycles remain unresolved and are rejected by vanilla exactly as before.
 */
@Mixin(ModelDiscovery.class)
public abstract class ModelDiscoveryParentOrderMixin {
    @org.spongepowered.asm.mixin.Unique private static final boolean nativeaccelerator$PARENT_FIRST =
            com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.booleanValue(
                    "model.parentFirstDiscoveryValidation", true);
    @ModifyArg(method = "resolve",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/model/ModelDiscovery;propagateValidity(Ljava/util/List;)V"),
            index = 0,
            require = 0)
    private List<?> nativeaccelerator$parentFirstValidation(List<?> models) {
        if (nativeaccelerator$PARENT_FIRST && models.size() > 1) {
            Collections.reverse(models);
        }
        return models;
    }
}
