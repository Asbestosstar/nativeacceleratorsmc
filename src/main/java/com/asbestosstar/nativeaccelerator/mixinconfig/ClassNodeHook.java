package com.asbestosstar.nativeaccelerator.mixinconfig;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Direct access to the ASM ClassNode supplied by Sponge Mixin immediately before and after a
 * Native Accelerator mixin is applied.
 *
 * Do not retain the ClassNode outside the callback. The transformation pipeline owns it and its
 * useful lifetime is the callback/transformation currently in progress.
 */
public interface ClassNodeHook {
    default void preApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo) {
    }

    default void postApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo) {
    }
}
