package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ChunkRenderBackpressure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$ResortTransparencyTask")
public abstract class SectionResortBackpressureMixin {
    @Redirect(method = "doTask", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;onSpinWait()V"), require = 0)
    private void nativeaccelerator$yieldWhenTranslucencyUploadStagingIsFull() {
        ChunkRenderBackpressure.pause();
    }
}
