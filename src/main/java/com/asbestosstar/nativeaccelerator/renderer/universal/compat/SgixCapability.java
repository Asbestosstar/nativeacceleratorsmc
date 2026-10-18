package com.asbestosstar.nativeaccelerator.renderer.universal.compat;

public record SgixCapability(SgixExtension extension, Status status, String reason) {
    public enum Status { RESEARCHED, PARTIAL, TESTED, HARDWARE_VERIFIED, UNSUPPORTED }

    public SgixCapability {
        extension = extension == null ? SgixExtension.POLYGON_OFFSET : extension;
        status = status == null ? Status.UNSUPPORTED : status;
        reason = reason == null ? "" : reason;
    }
}
