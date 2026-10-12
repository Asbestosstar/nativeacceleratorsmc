package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

/** Evidence status for one optional or required shaderpack feature. */
public record FeatureStatus(String feature, State state, String detail) {
    public enum State { SUPPORTED, PARTIAL, UNSUPPORTED, BLOCKED, UNKNOWN }
    public FeatureStatus {
        feature = GraphResource.requireId(feature, "feature name");
        if (state == null) throw new IllegalArgumentException("feature state is null");
        detail = detail == null ? "" : detail;
    }
}
