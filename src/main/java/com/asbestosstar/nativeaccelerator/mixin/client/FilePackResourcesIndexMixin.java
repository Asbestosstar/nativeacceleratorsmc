package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ZipResourceIndex;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Uses a sorted central-directory index for repeated ZIP-backed resource prefix scans. */
@Mixin(FilePackResources.class)
public abstract class FilePackResourcesIndexMixin {
    @Shadow @Final private String prefix;

    @Redirect(method = "listResources",
            at = @At(value = "INVOKE", target = "Ljava/util/zip/ZipFile;entries()Ljava/util/Enumeration;"),
            require = 0)
    private Enumeration<? extends ZipEntry> nativeaccelerator$indexedResources(ZipFile zip, PackType type,
            String namespace, String directory, PackResources.ResourceOutput output) {
        String root = nativeaccelerator$addPrefix(type.getDirectory() + "/" + namespace + "/");
        return ZipResourceIndex.entries(zip, root + directory + "/");
    }

    @Redirect(method = "getNamespaces",
            at = @At(value = "INVOKE", target = "Ljava/util/zip/ZipFile;entries()Ljava/util/Enumeration;"),
            require = 0)
    private Enumeration<? extends ZipEntry> nativeaccelerator$indexedNamespaces(ZipFile zip, PackType type) {
        return ZipResourceIndex.entries(zip, nativeaccelerator$addPrefix(type.getDirectory() + "/"));
    }

    private String nativeaccelerator$addPrefix(String path) {
        return this.prefix.isEmpty() ? path : this.prefix + "/" + path;
    }
}
