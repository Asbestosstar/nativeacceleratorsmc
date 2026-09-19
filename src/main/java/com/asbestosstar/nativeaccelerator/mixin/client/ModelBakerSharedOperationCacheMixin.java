package com.asbestosstar.nativeaccelerator.mixin.client;

import net.minecraft.client.resources.model.ModelBaker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.function.Function;

/**
 * Fast-paths repeated shared bake operations.
 *
 * <p>ModelBakery's concurrent operation cache is queried for every shared geometry/material
 * operation. {@code ConcurrentHashMap.computeIfAbsent} is required for a miss, but carries more
 * synchronization machinery than a plain read for the overwhelmingly common completed entry.
 * This keeps vanilla's atomic miss behavior while using {@link Map#get(Object)} for hits.</p>
 */
@Mixin(targets = "net.minecraft.client.resources.model.ModelBakery$ModelBakerImpl")
public abstract class ModelBakerSharedOperationCacheMixin {
    @Redirect(
            method = "compute",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"
            ),
            require = 0
    )
    private Object nativeaccelerator$fastSharedOperationHit(
            Map<Object, Object> cache, Object key, Function<Object, Object> compute) {
        Object cached = cache.get(key);
        return cached != null ? cached : cache.computeIfAbsent(key, compute);
    }
}

