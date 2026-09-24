package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import net.minecraft.server.packs.resources.ProfiledReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Copies vanilla's completed per-listener reload-profiler counters into the startup timing report.
 * These are accumulated task CPU times, not wall-clock intervals: parallel preparation may overlap.
 */
@Mixin(ProfiledReloadInstance.class)
public abstract class ProfiledReloadInstanceTimingMixin {
    private static final String PREFIX = "client.resource-reload.listener.";

    @Inject(method = "finish", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$recordListenerTimes(
            List<ProfiledReloadInstance.State> states,
            CallbackInfoReturnable<List<ProfiledReloadInstance.State>> cir) {
        for (ProfiledReloadInstance.State state : cir.getReturnValue()) {
            String name = listenerId(state.name());
            StartupTimer.recordDuration(PREFIX + name + ".prepare", state.preparationNanos().get());
            StartupTimer.recordDuration(PREFIX + name + ".apply", state.reloadNanos().get());
        }
    }

    private static String listenerId(String name) {
        if (name == null || name.isBlank()) return "unnamed";
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}

