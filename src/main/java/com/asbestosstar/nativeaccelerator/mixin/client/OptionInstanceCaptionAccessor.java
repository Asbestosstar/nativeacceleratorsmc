package com.asbestosstar.nativeaccelerator.mixin.client;

import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the graphics menu install a literal localized fallback caption after OptionInstance construction. */
@Mixin(OptionInstance.class)
public interface OptionInstanceCaptionAccessor {
    @Accessor("caption")
    @Mutable
    void nativeaccelerator$setCaption(Component caption);
}

