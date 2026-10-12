package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.util.List;

public final class ShaderpackResourceLayoutTest {
    public static void main(String[] args) {
        var declarations = List.of(
                new ShaderpackResource("texture", ShaderpackResourceKind.SAMPLER, "sampler2D", 1, 0, false, true),
                new ShaderpackResource("images", ShaderpackResourceKind.IMAGE, "image2D", 2, 2, false, false),
                new ShaderpackResource("globals", ShaderpackResourceKind.UNIFORM_BUFFER, "Block", 1, 0, false, true),
                new ShaderpackResource("state", ShaderpackResourceKind.STORAGE_BUFFER, "State", 1, -1, false, false),
                new ShaderpackResource("position", ShaderpackResourceKind.VERTEX_ATTRIBUTE, "vec3", 1, -1, false, true),
                new ShaderpackResource("normal", ShaderpackResourceKind.VERTEX_ATTRIBUTE, "vec3", 1, 0, false, true));
        var first = ShaderpackResourceLayout.allocate(declarations);
        var second = ShaderpackResourceLayout.allocate(List.of(
                declarations.get(4), declarations.get(1), declarations.get(5), declarations.get(3),
                declarations.get(0), declarations.get(2)));
        require(first.resources().equals(second.resources()), "allocation depends on declaration order");
        require(first.resource("images", ShaderpackResourceKind.IMAGE).binding() == 2, "explicit array base binding");
        require(first.resource("state", ShaderpackResourceKind.STORAGE_BUFFER).binding() == 0, "auto binding");
        require(first.resource("position", ShaderpackResourceKind.VERTEX_ATTRIBUTE).binding() == 1, "auto attribute follows explicit location");
        require(first.counts().get(ShaderpackResourceKind.VERTEX_ATTRIBUTE) == 2, "attribute count");

        expectFailure(() -> ShaderpackResourceLayout.allocate(List.of(
                new ShaderpackResource("a", ShaderpackResourceKind.SAMPLER, "sampler2D", 2, 3, false, true),
                new ShaderpackResource("b", ShaderpackResourceKind.SAMPLER, "sampler2D", 1, 4, false, true))), "overlapping ranges");
        expectFailure(() -> ShaderpackResourceLayout.allocate(List.of(
                new ShaderpackResource("same", ShaderpackResourceKind.IMAGE, "image2D"),
                new ShaderpackResource("same", ShaderpackResourceKind.IMAGE, "image2D"))), "duplicate names");
        expectFailure(() -> new ShaderpackResource("bad", ShaderpackResourceKind.SAMPLER, "sampler2D", 0, -1, false, true), "zero-length arrays");
        System.out.println("ShaderpackResourceLayoutTest: PASS");
    }

    private static void expectFailure(Runnable action, String label) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("accepted " + label);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
