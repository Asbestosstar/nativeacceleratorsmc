package com.asbestosstar.nativeaccelerator.mixinconfig;

/**
 * Early-startup-safe rule used by the mixin config plugin.
 *
 * Implementations must not reference Minecraft classes. Mixin config plugins run before normal
 * mod initialization and may execute while game classes are still being transformed.
 */
@FunctionalInterface
public interface MixinRule {
    MixinDecision decide(String targetClassName, String mixinClassName);
}
