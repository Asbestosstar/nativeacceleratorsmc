package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslProfile;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.TriangleMaterial;

public final class TriangleMaterialTest {
    public static void main(String[] args) {
        TriangleMaterial material = TriangleMaterial.load(TriangleMaterialTest.class.getClassLoader());
        if (!"vertex".equals(material.vertex().stage()) || !"fragment".equals(material.fragment().stage())) {
            throw new AssertionError("stage metadata");
        }
        if (!material.vertex().sourceHash().matches("[0-9a-f]{64}")
                || !material.fragment().sourceHash().matches("[0-9a-f]{64}")) {
            throw new AssertionError("source hash");
        }
        if (material.forProfile(GlslProfile.OPENGL).vertex().profile() != GlslProfile.OPENGL) {
            throw new AssertionError("profile conversion");
        }
        System.out.println("TriangleMaterialTest: PASS");
    }
}
