package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plain-main graph validation regression test; intentionally has no Minecraft/loader dependency. */
public final class ShaderProgramGraphTest {
    public static void main(String[] args) {
        var graph = new ShaderProgramGraph.Builder()
                .resource(new GraphResource("color", GraphResource.Kind.TEXTURE))
                .program(new ShaderProgram("draw", Map.of("vertex", "v", "fragment", "f")))
                .pass(new ShaderPass("clear", "draw", List.of(new ShaderPass.ResourceUse("color", ShaderPass.Access.WRITE))))
                .pass(new ShaderPass("sample", "draw", List.of(new ShaderPass.ResourceUse("color", ShaderPass.Access.READ)), Set.of("clear"), true))
                .feature(new FeatureStatus("compute", FeatureStatus.State.UNSUPPORTED, "not implemented"))
                .build();
        var result = graph.validate();
        check(result.valid(), result.errors().toString());
        check(result.executionOrder().equals(List.of("clear", "sample")), "declared ordering lost");
        check(result.hazards().size() == 1 && result.hazards().getFirst().type() == GraphHazard.Type.READ_AFTER_WRITE,
                "write/read hazard missing");
        try {
            graph.resources().clear();
            throw new AssertionError("mutable resource map");
        } catch (UnsupportedOperationException expected) { }

        var invalid = new ShaderProgramGraph.Builder()
                .resource(new GraphResource("uninitialized", GraphResource.Kind.BUFFER))
                .program(new ShaderProgram("p", Map.of()))
                .pass(new ShaderPass("read", "p", List.of(new ShaderPass.ResourceUse("uninitialized", ShaderPass.Access.READ))))
                .build().validate();
        check(!invalid.valid() && invalid.errors().stream().anyMatch(e -> e.contains("read before write")),
                "read-before-write was not rejected");
        try {
            new ShaderProgramGraph.Builder(new ShaderProgramGraph.Limits(1, 1, 1, 1))
                    .resource(new GraphResource("a", GraphResource.Kind.BUFFER))
                    .resource(new GraphResource("b", GraphResource.Kind.BUFFER));
            throw new AssertionError("resource limit not enforced");
        } catch (IllegalArgumentException expected) { }
        System.out.println("ShaderProgramGraphTest: PASS");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
