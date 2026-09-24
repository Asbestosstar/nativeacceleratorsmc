package com.asbestosstar.nativeaccelerator.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Native pointer access used only for bulk copies to/from the persistent decoded-texture cache. */
@Mixin(NativeImage.class)
public interface NativeImagePixelsAccessor {
    @Accessor("pixels")
    long nativeaccelerator$pixels();
}

