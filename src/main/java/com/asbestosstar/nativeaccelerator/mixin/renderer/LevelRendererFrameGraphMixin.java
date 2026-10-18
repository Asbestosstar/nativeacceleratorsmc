package com.asbestosstar.nativeaccelerator.mixin.renderer;

import com.asbestosstar.nativeaccelerator.renderer.LevelFrameGraphDispatch;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lifecycle observation plus a fail-open insertion seam immediately before the vanilla frame graph executes.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererFrameGraphMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
            at = @At("HEAD"), require = 0)
    private void nativeaccelerator$frameGraphBegin(CallbackInfo ci) {
        Object state = null;
        Object storage = null;
        try {
            LevelRendererFrameGraphAccessor access = (LevelRendererFrameGraphAccessor) this;
            state = access.nativeaccelerator$levelRenderState();
            storage = access.nativeaccelerator$submitNodeStorage();
        } catch (Throwable ignored) {
            // The optional accessor is deliberately not required for frame observation.
        }
        LevelFrameGraphDispatch.dispatch(LevelFrameGraphDispatch.Phase.FRAME_RENDER_BEGIN,
                this, state, storage);
    }

    @Redirect(method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"),
            require = 0)
    private void nativeaccelerator$beforeGraphExecute(FrameGraphBuilder graph,
                                                       GraphicsResourceAllocator allocator,
                                                       FrameGraphBuilder.Inspector inspector) {
        try {
            LevelRendererFrameGraphAccessor access = (LevelRendererFrameGraphAccessor) this;
            com.asbestosstar.nativeaccelerator.renderer.universal.ShaderpackPostProcessRuntime
                    .prepareConventionalPass(graph, access.nativeaccelerator$targets().main);
        } catch (Throwable failure) {
            System.err.println("[Native Accelerator] Conventional graph hook skipped: " + failure);
        }
        graph.execute(allocator, inspector);
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
            at = @At("RETURN"), require = 0)
    private void nativeaccelerator$frameGraphEnd(CallbackInfo ci) {
        Object state = null;
        Object storage = null;
        try {
            LevelRendererFrameGraphAccessor access = (LevelRendererFrameGraphAccessor) this;
            state = access.nativeaccelerator$levelRenderState();
            storage = access.nativeaccelerator$submitNodeStorage();
        } catch (Throwable ignored) {
            // Optional observation must never alter vanilla frame completion.
        }
        LevelFrameGraphDispatch.dispatch(LevelFrameGraphDispatch.Phase.FRAME_RENDER_END,
                this, state, storage);
    }
}
