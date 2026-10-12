package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import java.util.Objects;

/** Loader-neutral declaration of a named shader-program graph resource. */
public record GraphResource(String id, Kind kind, String format, int width, int height,
                            int layers, boolean external, boolean initiallyInitialized) {
    public enum Kind { TEXTURE, BUFFER, FRAMEBUFFER, OTHER }

    public GraphResource {
        id = requireId(id, "resource id");
        Objects.requireNonNull(kind, "kind");
        format = format == null ? "" : format.trim();
        if (width < 0 || height < 0 || layers < 0) throw new IllegalArgumentException("negative resource extent");
        if ((width == 0) != (height == 0)) throw new IllegalArgumentException("width and height must both be zero or positive");
        if (layers == 0 && (width > 0 || height > 0)) throw new IllegalArgumentException("sized resource needs at least one layer");
        if (external && !initiallyInitialized) throw new IllegalArgumentException("external resource must declare its initial contents");
    }

    public GraphResource(String id, Kind kind) {
        this(id, kind, "", 0, 0, 0, false, false);
    }

    static String requireId(String id, String label) {
        if (id == null || id.isBlank() || id.length() > 256) throw new IllegalArgumentException("invalid " + label);
        return id;
    }
}
