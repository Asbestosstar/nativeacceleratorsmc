package com.asbestosstar.nativeaccelerator.mixin.renderer;

import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the state used by LevelRenderer's frame-graph construction. */
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer")
public interface LevelRendererFrameGraphAccessor {
    @Accessor("levelRenderState")
    LevelRenderState nativeaccelerator$levelRenderState();

    @Accessor("submitNodeStorage")
    SubmitNodeStorage nativeaccelerator$submitNodeStorage();

    @Accessor("targets")
    LevelTargetBundle nativeaccelerator$targets();
}
