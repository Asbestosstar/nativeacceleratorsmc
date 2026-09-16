package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.cache.PersistentResourceCache;
import com.asbestosstar.nativeaccelerator.client.IdentifierInterner;
import com.asbestosstar.nativeaccelerator.client.ReloadResourceIndex;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Drops reload-scoped indexes while retaining safe per-open-ZipFile manifests across reloads. */
@Mixin(ReloadableResourceManager.class)
public abstract class ReloadResourceIndexLifecycleMixin {
    @Inject(method = "createReload", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$clearResourceIndex(Executor backgroundExecutor, Executor mainThreadExecutor,
            CompletableFuture<Unit> initialTask, List<PackResources> resourcePacks,
            CallbackInfoReturnable<ReloadInstance> cir) {
        PersistentResourceCache.beginReload();
        IdentifierInterner.clear();
        ReloadResourceIndex.clear();
        // ZipResourceIndex is keyed by the exact open ZipFile and is safe to retain across reloads.
        // A replaced/closed pack gets a different weak key; unchanged packs can reuse their lazy manifest.
    }
}
