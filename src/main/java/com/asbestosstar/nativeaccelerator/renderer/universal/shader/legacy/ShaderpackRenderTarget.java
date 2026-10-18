package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

/** Ordered color attachment selected by an active RENDERTARGETS/DRAWBUFFERS declaration. */
public record ShaderpackRenderTarget(int attachment, String name) {
    public ShaderpackRenderTarget {
        if (attachment < 0) throw new IllegalArgumentException("attachment is negative");
        name = name == null ? "" : name.trim();
    }
}
