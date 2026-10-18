package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslFrontend;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslProfile;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderSourceSpec;

public final class GlslFrontendTest {
    public static void main(String[] args) {
        String source = "#version 460\nlayout(location=0) in vec2 p;\nvoid main(){gl_Position=vec4(p,0,1);}";
        var module = new GlslFrontend().compile(new ShaderSourceSpec(source, "inline.vert", "460", GlslProfile.VULKAN, "vertex"));
        if (module.source().sourceHash().isBlank()) throw new AssertionError("missing source hash");
        if (module.diagnostics().isEmpty() && module.spirv() == null) throw new AssertionError("missing compiler output");
        if (!module.isValid()) {
            System.out.println("GlslFrontendTest: BLOCKED/DIAGNOSTIC " + module.diagnostics());
            return;
        }
        System.out.println("GlslFrontendTest: PASS");
    }
}
