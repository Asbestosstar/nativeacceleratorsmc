package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslProfile;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderModule;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderReflection;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderSourceSpec;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdValidationException;

import java.nio.ByteBuffer;
import java.util.List;

public final class UrdShaderValidationTest {
    public static void main(String[] args) {
        ShaderModule vertex = module("vertex", List.of(
                new ShaderReflection.InterfaceVariable("position", 0, "vec2"),
                new ShaderReflection.InterfaceVariable("color", 1, "vec3")),
                List.of(new ShaderReflection.InterfaceVariable("color", 0, "vec3")));
        ShaderModule fragment = module("fragment", List.of(
                new ShaderReflection.InterfaceVariable("color", 0, "vec3")),
                List.of(new ShaderReflection.InterfaceVariable("out_color", 0, "vec4")));
        UrdShaderValidation.validateTriangleInterface(vertex, fragment);

        expectFailure(() -> UrdShaderValidation.validateTriangleInterface(vertex,
                module("fragment", fragment.reflection().inputs(), List.of())), "fragment output");
        expectFailure(() -> UrdShaderValidation.validateTriangleInterface(vertex,
                module("fragment", List.of(new ShaderReflection.InterfaceVariable("color", 0, "vec4")),
                        fragment.reflection().outputs())), "interface type");
        expectFailure(() -> UrdShaderValidation.validateTriangleInterface(
                module("compute", vertex.reflection().inputs(), vertex.reflection().outputs()), fragment), "stage");
        System.out.println("UrdShaderValidationTest: PASS");
    }

    private static ShaderModule module(String stage, List<ShaderReflection.InterfaceVariable> inputs,
                                       List<ShaderReflection.InterfaceVariable> outputs) {
        ShaderSourceSpec source = new ShaderSourceSpec("#version 460\nvoid main(){}", "test." + stage,
                "460", GlslProfile.VULKAN, stage);
        return new ShaderModule(source, ByteBuffer.allocateDirect(4),
                new ShaderReflection(inputs, outputs, List.of(), 0), List.of());
    }

    private static void expectFailure(Runnable action, String label) {
        try {
            action.run();
            throw new AssertionError("accepted invalid " + label);
        } catch (UrdValidationException expected) {
        }
    }
}
