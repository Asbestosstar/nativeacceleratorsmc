package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.compat.SgixCapability;
import com.asbestosstar.nativeaccelerator.renderer.universal.compat.SgixExtension;
import com.asbestosstar.nativeaccelerator.renderer.universal.compat.SgixRegistry;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.isl.IslFrontend;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.isl.IslProfile;

public final class IslFrontendTest {
    public static void main(String[] args) {
        var module = new IslFrontend().compile("fixture.isl", "FB = color;", IslProfile.ISL_3_0_STRICT);
        if (module.tokens().isEmpty() || !"FB".equals(module.tokens().get(0).lexeme())) {
            throw new AssertionError("ISL lexer did not preserve identifiers");
        }
        if (module.isValid()) throw new AssertionError("unimplemented ISL parser was presented as valid");
        var registry = new SgixRegistry();
        if (registry.capability(SgixExtension.SPRITE).status() != SgixCapability.Status.UNSUPPORTED) {
            throw new AssertionError("unimplemented SGIX state was presented as supported");
        }
        System.out.println("IslFrontendTest: PASS (foundation; parser/runtime explicitly incomplete)");
    }
}
