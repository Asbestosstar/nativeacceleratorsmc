package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelJsonParseCache;
import java.io.Reader;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Reuses immutable parsed definitions for models whose exact JSON has not changed. */
@Mixin(ModelManager.class)
public abstract class ModelManagerModelParseCacheMixin {
    @Redirect(
            method = "lambda$loadBlockModels$2",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/model/cuboid/CuboidModel;fromStream(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/cuboid/CuboidModel;"
            )
    )
    private static CuboidModel nativeaccelerator$reuseParsedModel(Reader reader) {
        return ModelJsonParseCache.parse(reader);
    }
}
