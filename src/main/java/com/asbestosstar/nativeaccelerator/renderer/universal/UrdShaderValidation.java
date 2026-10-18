package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderModule;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderReflection;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdValidationException;

import java.util.List;

/** Validation shared by the first, deliberately small URD GLSL vertical slice. */
public final class UrdShaderValidation {
    private UrdShaderValidation() {
    }

    public static void validateTriangle(ShaderModule vertex, ShaderModule fragment) {
        validateTriangleInterface(vertex, fragment);
    }

    public static void validateTriangleInterface(ShaderModule vertex, ShaderModule fragment) {
        if (vertex == null || fragment == null || !vertex.isValid() || !fragment.isValid()) {
            throw new UrdValidationException("valid vertex and fragment shader modules are required");
        }
        if (vertex.reflection() == null || fragment.reflection() == null) {
            throw new UrdValidationException("shader reflection is required for the URD triangle contract");
        }
        if (!"vertex".equalsIgnoreCase(vertex.source().stage())) {
            throw new UrdValidationException("vertex module has stage " + vertex.source().stage());
        }
        if (!"fragment".equalsIgnoreCase(fragment.source().stage())) {
            throw new UrdValidationException("fragment module has stage " + fragment.source().stage());
        }
        rejectUnsupportedResources(vertex, "vertex");
        rejectUnsupportedResources(fragment, "fragment");

        requireLocation(vertex.reflection().inputs(), 0, "vertex location 0");
        requireLocation(vertex.reflection().inputs(), 1, "vertex location 1");
        ShaderReflection.InterfaceVariable vertexOutput = requireLocation(
                vertex.reflection().outputs(), 0, "vertex output location 0");
        ShaderReflection.InterfaceVariable fragmentInput = requireLocation(
                fragment.reflection().inputs(), 0, "fragment input location 0");
        requireLocation(fragment.reflection().outputs(), 0, "fragment output location 0");
        if (!compatibleType(vertexOutput.type(), fragmentInput.type())) {
            throw new UrdValidationException("vertex/fragment location 0 type mismatch: "
                    + vertexOutput.type() + " versus " + fragmentInput.type());
        }
    }

    private static void rejectUnsupportedResources(ShaderModule module, String stage) {
        if (!module.reflection().descriptors().isEmpty()) {
            throw new UrdValidationException(stage + " descriptors are outside the URD triangle contract");
        }
        if (module.reflection().pushConstantBytes() != 0) {
            throw new UrdValidationException(stage + " push constants are outside the URD triangle contract");
        }
    }

    private static ShaderReflection.InterfaceVariable requireLocation(
            List<ShaderReflection.InterfaceVariable> variables, int location, String label) {
        ShaderReflection.InterfaceVariable result = variables.stream()
                .filter(value -> value.location() == location)
                .findFirst()
                .orElse(null);
        if (result == null) throw new UrdValidationException("missing " + label);
        return result;
    }

    private static boolean compatibleType(String left, String right) {
        return left != null && right != null && left.equals(right);
    }
}
