package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable pass declaration; list position is the declared execution order. */
public record ShaderPass(String id, String program, List<ResourceUse> resources,
                         Set<String> after, boolean enabled) {
    public enum Access { READ, WRITE, READ_WRITE }
    public record ResourceUse(String resource, Access access) {
        public ResourceUse {
            resource = GraphResource.requireId(resource, "resource reference");
            if (access == null) throw new IllegalArgumentException("access is null");
        }
    }

    public ShaderPass {
        id = GraphResource.requireId(id, "pass id");
        program = GraphResource.requireId(program, "program id");
        resources = List.copyOf(resources == null ? List.of() : resources);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (ResourceUse use : resources) {
            if (use == null) throw new IllegalArgumentException("null resource use");
            if (!unique.add(use.resource())) throw new IllegalArgumentException("duplicate resource use: " + use.resource());
        }
        after = Set.copyOf(after == null ? Set.of() : after);
        if (after.contains(id)) throw new IllegalArgumentException("pass cannot depend on itself");
    }

    public ShaderPass(String id, String program, List<ResourceUse> resources) {
        this(id, program, resources, Set.of(), true);
    }
}
