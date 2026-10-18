package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.util.List;

public record ShaderReflection(
        List<InterfaceVariable> inputs,
        List<InterfaceVariable> outputs,
        List<Descriptor> descriptors,
        int pushConstantBytes) {
    public ShaderReflection {
        inputs = List.copyOf(inputs == null ? List.of() : inputs);
        outputs = List.copyOf(outputs == null ? List.of() : outputs);
        descriptors = List.copyOf(descriptors == null ? List.of() : descriptors);
        if (pushConstantBytes < 0) throw new IllegalArgumentException("negative push constants");
    }

    public record InterfaceVariable(String name, int location, String type) {
    }

    public record Descriptor(String name, int set, int binding, int resourceType, String type) {
    }
}
