package com.asbestosstar.nativeaccelerator.renderer.universal.compat;

import java.util.EnumMap;
import java.util.Map;

/** Explicit SGIX state/capability registry; SGIX is an OpenGL extension family, not a shader language. */
public final class SgixRegistry {
    private final Map<SgixExtension, SgixCapability> capabilities = new EnumMap<>(SgixExtension.class);

    public SgixRegistry() {
        for (SgixExtension extension : SgixExtension.values()) {
            capabilities.put(extension, new SgixCapability(extension, SgixCapability.Status.UNSUPPORTED,
                    "no runtime extension/state adapter is registered"));
        }
    }

    public SgixCapability capability(SgixExtension extension) {
        return capabilities.get(extension);
    }

    public void register(SgixCapability capability) {
        if (capability == null) throw new IllegalArgumentException("capability");
        capabilities.put(capability.extension(), capability);
    }
}
