package com.asbestosstar.nativeaccelerator.mixin.client;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Expands RenderPearl OpenGL's hard-coded two-submit window. A three-frame queue usually gives
 * older discrete GPUs enough CPU/GPU overlap to avoid the render thread waiting on every other
 * submission, while keeping latency and retained transient memory bounded.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
public abstract class GlCommandEncoderThroughputMixin {
    private static final int NATIVEACCELERATOR_FRAMES = Math.max(2, Math.min(4,
            Integer.getInteger("nativeaccelerator.renderer.opengl.framesInFlight", 3)));

    @Shadow @Final @Mutable private long[] fences;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$resizeFenceRing(CallbackInfo ci) {
        if (this.fences.length != NATIVEACCELERATOR_FRAMES) this.fences = new long[NATIVEACCELERATOR_FRAMES];
    }

    @ModifyConstant(method = "currentSubmitSlot", constant = @Constant(longValue = 2L), require = 0)
    private long nativeaccelerator$submitSlotWindow(long ignored) {
        return NATIVEACCELERATOR_FRAMES;
    }

    @ModifyConstant(method = "submit", constant = @Constant(longValue = 2L), require = 0)
    private long nativeaccelerator$submitWaitWindow(long ignored) {
        return NATIVEACCELERATOR_FRAMES;
    }

    @ModifyConstant(method = "awaitSubmit", constant = @Constant(longValue = 2L), require = 0)
    private long nativeaccelerator$completedWindow(long ignored) {
        return NATIVEACCELERATOR_FRAMES;
    }
}
