package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.cache.DeferredCacheWriter;
import com.asbestosstar.nativeaccelerator.startup.StartupStages;
import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import net.minecraft.client.ResourceLoadStateTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times the initial resource-pack reload, the phase usually responsible for the largest single slice of
 * Minecraft startup time.
 *
 * <p>{@code startReload} / {@code finishReload} bracket <em>every</em> reload, including the ones triggered
 * later by a resource-pack or language change. {@link StartupTimer} keeps the first occurrence of a stage
 * and ignores later ones, so what is reported is always the startup reload and the number stays comparable
 * between runs.</p>
 *
 * <p>Reversibility: both injections use {@code require = 0}; the handlers call only {@link StartupTimer}.</p>
 */
@Mixin(ResourceLoadStateTracker.class)
public abstract class ResourceLoadStateTrackerMixin {

    @Inject(method = {"startReload"}, at = @At("HEAD"), require = 0)
    private void nativeaccelerator$reloadBegin(CallbackInfo ci) {
        DeferredCacheWriter.beginReload();
        StartupTimer.begin(StartupStages.CLIENT_RESOURCE_RELOAD);
    }

    @Inject(method = {"finishReload"}, at = @At("HEAD"), require = 0)
    private void nativeaccelerator$reloadEnd(CallbackInfo ci) {
        StartupTimer.end(StartupStages.CLIENT_RESOURCE_RELOAD);
        DeferredCacheWriter.endReload();
    }
}

