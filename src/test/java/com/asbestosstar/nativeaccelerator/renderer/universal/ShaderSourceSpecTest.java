package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslProfile;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderSourceSpec;

public final class ShaderSourceSpecTest {
    public static void main(String[] args) {
        ShaderSourceSpec first = new ShaderSourceSpec("#version 460\nvoid main(){}", "triangle.vert", null,
                GlslProfile.VULKAN, "vertex");
        ShaderSourceSpec second = new ShaderSourceSpec("#version 460\nvoid main(){}", "triangle.vert", "460",
                GlslProfile.VULKAN, "vertex");
        if (!first.sourceHash().equals(second.sourceHash()) || !"460".equals(first.version())) {
            throw new AssertionError("source identity/version");
        }
        System.out.println("ShaderSourceSpecTest: PASS");
    }
}
