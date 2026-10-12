package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.util.HashSet;
import java.util.Set;

public final class StandardUniformRegistryTest {
    public static void main(String[] args) {
        require(StandardUniformRegistry.find("ModelViewMat").orElseThrow().type().equals("mat4"), "model-view matrix type");
        require(StandardUniformRegistry.find("ScreenSize").orElseThrow().type().equals("vec2"), "screen size type");
        require(StandardUniformRegistry.isStandard("GameTime"), "standard name lookup");
        require(!StandardUniformRegistry.isStandard("mod_CustomUniform"), "unknown custom uniform lookup");
        Set<String> names = new HashSet<>();
        for (var uniform : StandardUniformRegistry.all()) {
            require(!uniform.name().isBlank() && !uniform.type().isBlank(), "empty registry entry");
            require(names.add(uniform.name()), "duplicate registry name " + uniform.name());
        }
        boolean immutable = false;
        try { StandardUniformRegistry.byName().clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        require(immutable, "registry map is mutable");
        System.out.println("StandardUniformRegistryTest: PASS (" + names.size() + " uniforms)");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
