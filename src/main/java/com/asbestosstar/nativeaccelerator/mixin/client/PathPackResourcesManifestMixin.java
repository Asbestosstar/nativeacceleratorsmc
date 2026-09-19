package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.PathResourceIndex;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.List;

/** Uses the lazy jar-filesystem namespace manifest for PathPackResources.listPath when safe. */
@Mixin(PathPackResources.class)
public abstract class PathPackResourcesManifestMixin {
    @Inject(method = "listPath", at = @At("HEAD"), cancellable = true, require = 0)
    private static void nativeaccelerator$lazyManifest(String namespace, Path topDir,
            List<String> decomposedPrefixPath, PackResources.ResourceOutput output, CallbackInfo ci) {
        if (PathResourceIndex.tryList(namespace, topDir, decomposedPrefixPath, output)) ci.cancel();
    }
}

