package com.asbestosstar.nativeaccelerator.mixin.client;

import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Opt-in access to Minecraft's built-in per-listener resource reload profiler.
 *
 * <p>Vanilla selects {@code ProfiledReloadInstance} only when its resource-manager logger is at
 * debug level. The profiled implementation retains the normal listener order and executors, merely
 * accounting preparation/apply task time and logging the result after reload. Enable it with
 * {@code -Dnativeaccelerator.reload.profile=true}; leave it off for normal throughput runs.</p>
 */
@Mixin(ReloadableResourceManager.class)
public abstract class ReloadListenerProfilingMixin {
    private static final String PROFILE_PROPERTY = "nativeaccelerator.reload.profile";

    @Redirect(
            method = "createReload",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;isDebugEnabled()Z"),
            require = 0)
    private boolean nativeaccelerator$enableVanillaListenerProfiler(Logger logger) {
        return com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.booleanValue("reload.profile", false) || logger.isDebugEnabled();
    }
}

