package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable loader-neutral shader program/pass graph. Pass order is retained exactly as declared. */
public record ShaderProgramGraph(Map<String, GraphResource> resources,
                                 Map<String, ShaderProgram> programs,
                                 List<ShaderPass> passes,
                                 Map<String, FeatureStatus> features) {
    public static final Limits DEFAULT_LIMITS = new Limits(256, 256, 4096, 4096);
    public record Limits(int maxResources, int maxPrograms, int maxPasses, int maxUses) {
        public Limits {
            if (maxResources < 0 || maxPrograms < 0 || maxPasses < 0 || maxUses < 0)
                throw new IllegalArgumentException("negative graph limit");
        }
    }
    public record Validation(List<String> errors, List<GraphHazard> hazards, List<String> executionOrder) {
        public Validation {
            errors = List.copyOf(errors);
            hazards = List.copyOf(hazards);
            executionOrder = List.copyOf(executionOrder);
        }
        public boolean valid() { return errors.isEmpty(); }
    }

    public ShaderProgramGraph {
        resources = immutableMap(resources);
        programs = immutableMap(programs);
        passes = List.copyOf(passes == null ? List.of() : passes);
        features = immutableMap(features);
    }

    private static <T> Map<String, T> immutableMap(Map<String, T> source) {
        LinkedHashMap<String, T> copy = new LinkedHashMap<>();
        if (source != null) source.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) throw new IllegalArgumentException("null/blank graph entry");
            if (copy.putIfAbsent(key, value) != null) throw new IllegalArgumentException("duplicate graph entry: " + key);
        });
        return java.util.Collections.unmodifiableMap(copy);
    }

    /** Validates references, initialization, explicit dependencies, and read/write hazards within budgets. */
    public Validation validate() { return validate(DEFAULT_LIMITS); }
    public Validation validate(Limits limits) {
        if (limits == null) throw new IllegalArgumentException("limits is null");
        List<String> errors = new ArrayList<>();
        List<GraphHazard> hazards = new ArrayList<>();
        List<String> order = new ArrayList<>();
        if (resources.size() > limits.maxResources()) errors.add("resource limit exceeded");
        if (programs.size() > limits.maxPrograms()) errors.add("program limit exceeded");
        if (passes.size() > limits.maxPasses()) errors.add("pass limit exceeded");
        // Fail closed before traversing oversized externally-constructed graphs.
        if (!errors.isEmpty()) return new Validation(errors, hazards, order);
        int uses = 0;
        Set<String> passIds = new HashSet<>();
        Map<String, ShaderPass> byId = new LinkedHashMap<>();
        for (ShaderPass pass : passes) {
            if (pass == null) { errors.add("null pass"); continue; }
            uses += pass.resources().size();
            if (!passIds.add(pass.id())) errors.add("duplicate pass: " + pass.id());
            byId.putIfAbsent(pass.id(), pass);
            if (!programs.containsKey(pass.program())) errors.add("missing program " + pass.program() + " in pass " + pass.id());
            for (String dep : pass.after()) if (!byId.containsKey(dep) && !passIds.contains(dep)) {
                // Resolve forward references after collecting all IDs below.
            }
        }
        if (uses > limits.maxUses()) errors.add("resource-use limit exceeded");
        for (ShaderPass pass : passes) {
            if (pass == null) continue;
            for (String dep : pass.after()) if (!byId.containsKey(dep)) errors.add("unknown dependency " + dep + " in pass " + pass.id());
        }
        List<ShaderPass> enabled = passes.stream().filter(p -> p != null && p.enabled()).toList();
        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < enabled.size(); i++) {
            ShaderPass p = enabled.get(i); position.put(p.id(), i); order.add(p.id());
            for (String dep : p.after()) {
                Integer dependencyPosition = position.get(dep);
                if (dependencyPosition == null) errors.add("dependency must precede pass " + p.id() + ": " + dep);
            }
        }
        Map<String, Boolean> initialized = new HashMap<>();
        resources.forEach((id, resource) -> initialized.put(id, resource.initiallyInitialized()));
        Map<String, ShaderPass> lastWriter = new HashMap<>();
        Map<String, ShaderPass> lastAccess = new HashMap<>();
        Map<String, Boolean> lastWasWrite = new HashMap<>();
        for (ShaderPass pass : enabled) {
            Set<String> touched = new HashSet<>();
            for (ShaderPass.ResourceUse use : pass.resources()) {
                GraphResource resource = resources.get(use.resource());
                if (resource == null) { errors.add("unknown resource " + use.resource() + " in pass " + pass.id()); continue; }
                boolean read = use.access() != ShaderPass.Access.WRITE;
                boolean write = use.access() != ShaderPass.Access.READ;
                if (read && !initialized.getOrDefault(resource.id(), false)) errors.add("read before write: " + resource.id() + " in " + pass.id());
                ShaderPass prior = lastAccess.get(resource.id());
                if (prior != null && prior != pass) {
                    GraphHazard.Type type = write ? (Boolean.TRUE.equals(lastWasWrite.get(resource.id()))
                            ? GraphHazard.Type.WRITE_AFTER_WRITE : GraphHazard.Type.WRITE_AFTER_READ)
                            : GraphHazard.Type.READ_AFTER_WRITE;
                    hazards.add(new GraphHazard(resource.id(), prior.id(), pass.id(), type));
                    Integer priorPos = position.get(prior.id()), currentPos = position.get(pass.id());
                    if (priorPos != null && currentPos != null && priorPos >= currentPos)
                        errors.add("resource hazard ordering invalid: " + prior.id() + " -> " + pass.id());
                }
                if (write) initialized.put(resource.id(), true);
                lastWriter.putIfAbsent(resource.id(), pass);
                lastAccess.put(resource.id(), pass);
                lastWasWrite.put(resource.id(), write);
                touched.add(resource.id());
            }
        }
        return new Validation(errors, hazards, order);
    }

    public static final class Builder {
        private final Map<String, GraphResource> resources = new LinkedHashMap<>();
        private final Map<String, ShaderProgram> programs = new LinkedHashMap<>();
        private final List<ShaderPass> passes = new ArrayList<>();
        private final Map<String, FeatureStatus> features = new LinkedHashMap<>();
        private final Limits limits;
        private int uses;

        public Builder() { this(DEFAULT_LIMITS); }
        public Builder(Limits limits) { this.limits = java.util.Objects.requireNonNull(limits); }
        public Builder resource(GraphResource value) {
            if (resources.size() >= limits.maxResources()) throw new IllegalArgumentException("resource limit exceeded");
            put(resources, value.id(), value); return this;
        }
        public Builder program(ShaderProgram value) {
            if (programs.size() >= limits.maxPrograms()) throw new IllegalArgumentException("program limit exceeded");
            put(programs, value.id(), value); return this;
        }
        public Builder pass(ShaderPass value) {
            if (passes.size() >= limits.maxPasses()) throw new IllegalArgumentException("pass limit exceeded");
            uses += value.resources().size();
            if (uses > limits.maxUses()) throw new IllegalArgumentException("resource-use limit exceeded");
            if (passes.stream().anyMatch(p -> p.id().equals(value.id()))) throw new IllegalArgumentException("duplicate pass: " + value.id());
            passes.add(value); return this;
        }
        public Builder feature(FeatureStatus value) { put(features, value.feature(), value); return this; }
        public ShaderProgramGraph build() { return new ShaderProgramGraph(resources, programs, passes, features); }
        private static <T> void put(Map<String,T> map, String id, T value) {
            if (value == null) throw new IllegalArgumentException("graph value is null");
            if (map.putIfAbsent(id, value) != null) throw new IllegalArgumentException("duplicate graph entry: " + id);
        }
    }
}
