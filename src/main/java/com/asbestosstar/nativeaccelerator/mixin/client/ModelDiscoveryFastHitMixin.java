package com.asbestosstar.nativeaccelerator.mixin.client;

import it.unimi.dsi.fastutil.objects.Object2ObjectFunction;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.client.resources.model.ModelDiscovery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Avoids fastutil computeIfAbsent machinery on already-discovered dependency hits. */
@Mixin(ModelDiscovery.class)
public abstract class ModelDiscoveryFastHitMixin {
    @Redirect(method = "getOrCreateModel",
            at = @At(value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;computeIfAbsent(Ljava/lang/Object;Lit/unimi/dsi/fastutil/objects/Object2ObjectFunction;)Ljava/lang/Object;"),
            require = 0)
    private Object nativeaccelerator$fastDiscoveryHit(Object2ObjectMap<Object, Object> map, Object key,
            Object2ObjectFunction<Object, Object> loader) {
        Object existing = map.get(key);
        return existing != null ? existing : map.computeIfAbsent(key, loader);
    }
}

