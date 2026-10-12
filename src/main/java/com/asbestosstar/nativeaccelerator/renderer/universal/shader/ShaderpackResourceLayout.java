package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validated, deterministic binding assignment for one shaderpack program interface. */
public final class ShaderpackResourceLayout {
    private final List<BoundResource> resources;
    private final Map<ShaderpackResourceKind, Integer> counts;

    private ShaderpackResourceLayout(List<BoundResource> resources) {
        this.resources = List.copyOf(resources);
        EnumMap<ShaderpackResourceKind, Integer> sizes = new EnumMap<>(ShaderpackResourceKind.class);
        for (BoundResource resource : resources) {
            sizes.merge(resource.resource().kind(), 1, Integer::sum);
        }
        this.counts = Map.copyOf(sizes);
    }

    public static ShaderpackResourceLayout allocate(List<ShaderpackResource> declarations) {
        Objects.requireNonNull(declarations, "declarations");
        List<ShaderpackResource> sorted = new ArrayList<>(declarations.size());
        Set<String> names = new HashSet<>();
        for (ShaderpackResource declaration : declarations) {
            Objects.requireNonNull(declaration, "declaration");
            if (!names.add(declaration.name())) {
                throw new IllegalArgumentException("duplicate resource name: " + declaration.name());
            }
            sorted.add(declaration);
        }
        sorted.sort(Comparator.comparing((ShaderpackResource r) -> r.kind().ordinal())
                .thenComparingInt(r -> r.requestedBinding() == ShaderpackResource.AUTO_BINDING ? Integer.MAX_VALUE : r.requestedBinding())
                .thenComparing(ShaderpackResource::name));

        EnumMap<ShaderpackResourceKind, Set<Integer>> occupied = new EnumMap<>(ShaderpackResourceKind.class);
        for (ShaderpackResourceKind kind : ShaderpackResourceKind.values()) occupied.put(kind, new HashSet<>());
        for (ShaderpackResource resource : sorted) {
            if (resource.requestedBinding() == ShaderpackResource.AUTO_BINDING) continue;
            Set<Integer> slots = occupied.get(resource.kind());
            for (int i = 0; i < resource.arrayLength(); i++) {
                int slot = Math.addExact(resource.requestedBinding(), i);
                if (!slots.add(slot)) {
                    throw new IllegalArgumentException("binding collision for " + resource.kind() + " at " + slot);
                }
            }
        }

        List<BoundResource> assigned = new ArrayList<>(sorted.size());
        for (ShaderpackResource resource : sorted) {
            Set<Integer> slots = occupied.get(resource.kind());
            int binding = resource.requestedBinding();
            if (binding == ShaderpackResource.AUTO_BINDING) {
                binding = 0;
                while (rangeOccupied(slots, binding, resource.arrayLength())) binding = Math.addExact(binding, 1);
                for (int i = 0; i < resource.arrayLength(); i++) slots.add(binding + i);
            }
            assigned.add(new BoundResource(resource, binding));
        }
        assigned.sort(Comparator.comparing((BoundResource r) -> r.resource().kind().ordinal())
                .thenComparingInt(BoundResource::binding).thenComparing(r -> r.resource().name()));
        return new ShaderpackResourceLayout(assigned);
    }

    public List<BoundResource> resources() { return resources; }
    public Map<ShaderpackResourceKind, Integer> counts() { return counts; }
    public BoundResource resource(String name, ShaderpackResourceKind kind) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        return resources.stream().filter(r -> r.resource().kind() == kind && r.resource().name().equals(name))
                .findFirst().orElse(null);
    }

    private static boolean rangeOccupied(Set<Integer> slots, int first, int length) {
        for (int i = 0; i < length; i++) if (slots.contains(Math.addExact(first, i))) return true;
        return false;
    }

    public record BoundResource(ShaderpackResource resource, int binding) {
        public BoundResource {
            Objects.requireNonNull(resource, "resource");
            if (binding < 0) throw new IllegalArgumentException("binding must be non-negative");
        }
    }
}
