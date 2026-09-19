package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

/** User-facing Native Accelerator renderer preference, independent of Minecraft's fixed enum. */
public enum GraphicsApiChoice implements StringRepresentable {
    DEFAULT("default", "nativeaccelerator.graphicsApi.default"),
    OPENGL("opengl", "nativeaccelerator.graphicsApi.opengl"),
    VULKAN("vulkan", "nativeaccelerator.graphicsApi.vulkan"),
    METAL("metal", "nativeaccelerator.graphicsApi.metal");

    public static final Codec<GraphicsApiChoice> CODEC = StringRepresentable.fromEnum(GraphicsApiChoice::values);

    private final String serializedName;
    private final Component caption;

    GraphicsApiChoice(String serializedName, String translationKey) {
        this.serializedName = serializedName;
        this.caption = Component.translatable(translationKey);
    }

    public Component caption() {
        return this.caption;
    }

    @Override
    public String getSerializedName() {
        return this.serializedName;
    }

    public static GraphicsApiChoice fromSerializedName(String name) {
        if (name != null) {
            for (GraphicsApiChoice value : values()) {
                if (value.serializedName.equalsIgnoreCase(name.trim())) return value;
            }
        }
        return DEFAULT;
    }
}
