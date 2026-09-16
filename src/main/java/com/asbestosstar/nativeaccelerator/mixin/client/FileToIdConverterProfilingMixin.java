package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelPipelineProfiler;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/** Profiles FileToIdConverter discovery even when the high-throughput replacement is disabled. */
@Mixin(FileToIdConverter.class)
public abstract class FileToIdConverterProfilingMixin {
    @Shadow public abstract String prefix();
    @Unique private static final ThreadLocal<Long> nativeaccelerator$resourceListStart = new ThreadLocal<>();
    @Unique private static final ThreadLocal<Long> nativeaccelerator$stackListStart = new ThreadLocal<>();

    @Inject(method = "listMatchingResources", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$listBegin(ResourceManager manager,
            CallbackInfoReturnable<Map<Identifier, Resource>> cir) {
        nativeaccelerator$resourceListStart.set(ModelPipelineProfiler.start());
    }

    @Inject(method = "listMatchingResources", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$listEnd(ResourceManager manager,
            CallbackInfoReturnable<Map<Identifier, Resource>> cir) {
        Long started = nativeaccelerator$resourceListStart.get();
        nativeaccelerator$resourceListStart.remove();
        if (started != null && started != 0L) {
            ModelPipelineProfiler.record("resource.enumerate." + nativeaccelerator$prefixName(),
                    System.nanoTime() - started, cir.getReturnValue().size());
        }
    }

    @Inject(method = "listMatchingResourceStacks", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$stackBegin(ResourceManager manager,
            CallbackInfoReturnable<Map<Identifier, List<Resource>>> cir) {
        nativeaccelerator$stackListStart.set(ModelPipelineProfiler.start());
    }

    @Inject(method = "listMatchingResourceStacks", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$stackEnd(ResourceManager manager,
            CallbackInfoReturnable<Map<Identifier, List<Resource>>> cir) {
        Long started = nativeaccelerator$stackListStart.get();
        nativeaccelerator$stackListStart.remove();
        if (started != null && started != 0L) {
            ModelPipelineProfiler.record("resource.enumerate-stacks." + nativeaccelerator$prefixName(),
                    System.nanoTime() - started, cir.getReturnValue().size());
        }
    }

    @Unique
    private String nativeaccelerator$prefixName() {
        String value = this.prefix();
        return value == null || value.isBlank() ? "root" : value.replace('/', '.');
    }
}
