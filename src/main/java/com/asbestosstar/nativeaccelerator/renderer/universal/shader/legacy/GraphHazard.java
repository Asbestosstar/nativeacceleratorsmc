package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

/** Resource ordering hazard between two passes. */
public record GraphHazard(String resource, String earlierPass, String laterPass, Type type) {
    public enum Type { READ_AFTER_WRITE, WRITE_AFTER_READ, WRITE_AFTER_WRITE }
    public GraphHazard {
        resource = GraphResource.requireId(resource, "hazard resource");
        earlierPass = GraphResource.requireId(earlierPass, "earlier pass");
        laterPass = GraphResource.requireId(laterPass, "later pass");
        if (type == null) throw new IllegalArgumentException("hazard type is null");
    }
}
