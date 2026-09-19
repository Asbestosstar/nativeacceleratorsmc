package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelPipelineProfiler;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/** Separates directory-pack filesystem walking from ReloadResourceIndex lookup overhead. */
@Mixin(PathPackResources.class)
public abstract class PathPackResourcesProfilingMixin {
    @Unique private static final ThreadLocal<Long> nativeaccelerator$pathListStarted = new ThreadLocal<>();

    @Inject(method = "listResources(Ljava/nio/file/Path;Ljava/lang/String;Ljava/lang/String;Lnet/minecraft/server/packs/PackResources$ResourceOutput;)V",
            at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$pathListBegin(Path topPath, String namespace, String directory,
            PackResources.ResourceOutput output, CallbackInfo ci) {
        nativeaccelerator$pathListStarted.set(ModelPipelineProfiler.start());
    }

    @Inject(method = "listResources(Ljava/nio/file/Path;Ljava/lang/String;Ljava/lang/String;Lnet/minecraft/server/packs/PackResources$ResourceOutput;)V",
            at = @At("RETURN"), require = 0)
    private static void nativeaccelerator$pathListEnd(Path topPath, String namespace, String directory,
            PackResources.ResourceOutput output, CallbackInfo ci) {
        Long started = nativeaccelerator$pathListStarted.get();
        nativeaccelerator$pathListStarted.remove();
        if (started != null && started != 0L) {
            ModelPipelineProfiler.end("resource.pack.path.list", started);
        }
    }
}
