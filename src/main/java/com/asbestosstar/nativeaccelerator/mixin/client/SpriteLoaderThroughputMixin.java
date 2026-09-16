package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelDagProfiler;
import com.asbestosstar.nativeaccelerator.client.ModelPipelineProfiler;
import com.asbestosstar.nativeaccelerator.client.ModelWorkScheduler;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Batches per-sprite-source tasks and exposes per-atlas wall-clock completion in the DAG report. */
@Mixin(SpriteLoader.class)
public abstract class SpriteLoaderThroughputMixin {
    @Unique private static final boolean nativeaccelerator$BATCH_SPRITES =
            com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.booleanValue("atlas.batchSpriteSuppliers", true);
    @Unique private static final ThreadLocal<Long> nativeaccelerator$atlasStarted = new ThreadLocal<>();
    @Unique private static final ThreadLocal<Long> nativeaccelerator$stitchStarted = new ThreadLocal<>();

    @Inject(method = "runSpriteSuppliers", at = @At("HEAD"), cancellable = true, require = 0)
    private static void nativeaccelerator$batchSpriteSuppliers(SpriteResourceLoader resourceLoader,
            List<SpriteSource.Loader> sprites, Executor executor,
            CallbackInfoReturnable<CompletableFuture<List<SpriteContents>>> cir) {
        if (!nativeaccelerator$BATCH_SPRITES) return;
        CompletableFuture<List<SpriteContents>> future = ModelWorkScheduler.mapIndexed(
                sprites.size(), executor, i -> sprites.get(i).get(resourceLoader), "atlas.sprite-source.dynamic")
                .thenApply(values -> {
                    ArrayList<SpriteContents> result = new ArrayList<>(values.size());
                    for (SpriteContents value : values) if (value != null) result.add(value);
                    return List.copyOf(result);
                });
        cir.setReturnValue(future);
    }

    @Inject(method = "loadAndStitch", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$atlasBegin(ResourceManager manager, Identifier atlasInfoLocation,
            int maxMipmapLevels, Executor taskExecutor, Set<MetadataSectionType<?>> additionalMetadata,
            CallbackInfoReturnable<CompletableFuture<SpriteLoader.Preparations>> cir) {
        nativeaccelerator$atlasStarted.set(ModelDagProfiler.begin());
    }

    @Inject(method = "loadAndStitch", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$atlasReturn(ResourceManager manager, Identifier atlasInfoLocation,
            int maxMipmapLevels, Executor taskExecutor, Set<MetadataSectionType<?>> additionalMetadata,
            CallbackInfoReturnable<CompletableFuture<SpriteLoader.Preparations>> cir) {
        Long started = nativeaccelerator$atlasStarted.get();
        nativeaccelerator$atlasStarted.remove();
        if (started != null) {
            ModelDagProfiler.track("atlas." + nativeaccelerator$safe(atlasInfoLocation.toString()), cir.getReturnValue(), started);
        }
    }

    @Inject(method = "stitch", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$stitchBegin(List<SpriteContents> sprites, int maxMipmapLevels, Executor executor,
            CallbackInfoReturnable<SpriteLoader.Preparations> cir) {
        nativeaccelerator$stitchStarted.set(ModelPipelineProfiler.start());
    }

    @Inject(method = "stitch", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$stitchEnd(List<SpriteContents> sprites, int maxMipmapLevels, Executor executor,
            CallbackInfoReturnable<SpriteLoader.Preparations> cir) {
        Long started = nativeaccelerator$stitchStarted.get();
        nativeaccelerator$stitchStarted.remove();
        if (started != null && started != 0L) {
            ModelPipelineProfiler.record("atlas.stitch", System.nanoTime() - started, sprites.size());
        }
    }

    @Unique private static String nativeaccelerator$safe(String value) {
        return value.replace(':', '.').replace('/', '.');
    }
}
