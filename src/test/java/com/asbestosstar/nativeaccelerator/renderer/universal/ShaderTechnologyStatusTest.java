package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderTechnology;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderTechnologyStatus;

public final class ShaderTechnologyStatusTest {
    public static void main(String[] args) {
        for (ShaderTechnology technology : ShaderTechnology.values()) {
            String status = ShaderTechnologyStatus.status(technology);
            if (status == null || status.isBlank()) throw new AssertionError("missing status for " + technology);
            if ((technology == ShaderTechnology.ISL || technology == ShaderTechnology.SGIX)
                    && status.startsWith("SUPPORTED")) {
                throw new AssertionError("unsupported technology was presented as supported: " + technology);
            }
        }
        System.out.println("ShaderTechnologyStatusTest: PASS (explicit technology limitations)");
    }
}
