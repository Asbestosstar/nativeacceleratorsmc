package com.asbestosstar.nativeaccelerator.mixin.common;

import net.minecraft.world.level.levelgen.synth.GradientNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Primitive state needed by the batched native Perlin kernel. */
@Mixin(GradientNoise.class)
public interface GradientNoiseAccessor {
    @Accessor("perms")
    byte[] nativeaccelerator$permutations();

    @Accessor("offsetX")
    double nativeaccelerator$offsetX();

    @Accessor("offsetY")
    double nativeaccelerator$offsetY();

    @Accessor("offsetZ")
    double nativeaccelerator$offsetZ();
}

