package com.asbestosstar.nativeaccelerator.mixin.common;

import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the primitive DensityBuffer storage to the bulk worldgen kernel without reflection. */
@Mixin(DensityBuffer.class)
public interface DensityBufferAccessor {
    @Accessor("values")
    float[] nativeaccelerator$values();
}

