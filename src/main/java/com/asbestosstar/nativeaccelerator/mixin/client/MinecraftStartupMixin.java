package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.cache.DeferredCacheWriter;
import com.asbestosstar.nativeaccelerator.startup.StartupStages;
import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import com.asbestosstar.nativeaccelerator.renderer.metal.GraphicsBackendPreference;
import com.asbestosstar.nativeaccelerator.renderer.RendererBackendRuntime;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Startup timing for {@code net.minecraft.client.Minecraft}.
 *
 * <p>Three parts of client startup live here:</p>
 * <ul>
 *   <li>construction ({@code Minecraft(GameConfig)}) - the client object, its resource manager and its
 *       window/GL setup;</li>
 *   <li>the game loop ({@code run()}), recorded as a <em>lifetime</em> stage because it ends only when the
 *       game is closed;</li>
 *   <li>{@code onGameLoadFinished} - the exact point the loading overlay is dismissed and the initial
 *       screen is shown. Marking it gives the headline "time to a playable client" and triggers the first
 *       report, so the numbers are visible in the log without quitting.</li>
 * </ul>
 *
 * <p>The {@code client.loading} stage opened by {@code LoadingOverlayMixin} is closed here and by that
 * mixin's {@code tick}, whichever runs first; {@code StartupTimer} makes the second close a no-op.</p>
 *
 * <p>The {@code client.resource-reload} stage is <em>not</em> started here: it is owned by
 * {@code ResourceLoadStateTrackerMixin}, which sees {@code startReload}/{@code finishReload} directly.</p>
 *
 * <p>Reversibility: every injection uses {@code require = 0}; the handlers call only {@link StartupTimer},
 * which never throws and is disabled by {@code -Dnativeaccelerator.startup.timing=false}.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftStartupMixin {

    @Inject(method = "<init>(Lnet/minecraft/client/main/GameConfig;)V", at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$minecraftInitBegin(CallbackInfo ci) {
        StartupTimer.begin(StartupStages.CLIENT_MINECRAFT_INIT);
    }

    @Inject(method = "<init>(Lnet/minecraft/client/main/GameConfig;)V", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$minecraftInitEnd(CallbackInfo ci) {
        StartupTimer.end(StartupStages.CLIENT_MINECRAFT_INIT);
    }

    @Inject(method = "run()V", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$gameLoopBegin(CallbackInfo ci) {
        StartupTimer.beginLifetime(StartupStages.CLIENT_GAME_LOOP);
    }

    @Inject(method = "onGameLoadFinished", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$gameLoadFinished(CallbackInfo ci) {
        StartupTimer.end(StartupStages.CLIENT_LOADING);
        StartupTimer.mark(StartupStages.CLIENT_TITLE_SCREEN);
        StartupTimer.finish("client load finished (first screen shown)");
        DeferredCacheWriter.startupComplete();
    }
    /**
     * Replace only the backend-list lookup during client construction. This makes Metal selection
     * independent of Minecraft's fixed PreferredGraphicsApi enum and independent of the options GUI.
     */
    @Redirect(
            method = "<init>(Lnet/minecraft/client/main/GameConfig;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/PreferredGraphicsApi;getBackendsToTry()[Lcom/mojang/renderpearl/api/device/GpuBackend;"))
    private GpuBackend[] nativeaccelerator$selectGraphicsBackends(PreferredGraphicsApi preferred) {
        return GraphicsBackendPreference.backendsToTry(preferred);
    }

    /**
     * Minecraft's vanilla DEFAULT branch performs a separate Vulkan availability probe even before
     * backend creation.  Metal is mirrored as DEFAULT in options.txt for fail-open compatibility, so
     * suppress that probe when Native Accelerator explicitly selected Metal or OpenGL.
     */
    @Redirect(
            method = "<init>(Lnet/minecraft/client/main/GameConfig;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;checkBackendAvailable()Lcom/mojang/renderpearl/api/device/BackendCreationException;"),
            require = 1)
    private @Nullable BackendCreationException nativeaccelerator$conditionalVulkanAvailabilityProbe() {
        if (!GraphicsBackendPreference.shouldRunVanillaVulkanProbe()) {
            System.out.println("[Native Accelerator] Explicit Metal/OpenGL selection: skipping Minecraft's Vulkan availability probe");
            return null;
        }
        return VulkanBackend.checkBackendAvailable();
    }

    /**
     * Observe the backend that actually succeeds. Vulkan-specific Native Accelerator services are
     * started only here, after a Vulkan GpuDevice exists. Metal/OpenGL never touch the Vulkan
     * companion library or Vulkan device probe.
     */
    @Redirect(
            method = "<init>(Lnet/minecraft/client/main/GameConfig;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;"),
            require = 1)
    private GpuDevice nativeaccelerator$createGraphicsDevice(GpuBackend backend, GpuDebugOptions options)
            throws BackendCreationException {
        GpuDevice device = backend.createDevice(options);
        RendererBackendRuntime.backendCreated(backend);
        return device;
    }

}
