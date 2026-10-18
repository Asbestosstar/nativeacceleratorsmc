package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small deterministic render-graph validator used by real adapters and reference tests. */
public final class UrdRenderGraph {
    private final Map<String, Boolean> resources = new LinkedHashMap<>();
    private final Map<String, Boolean> imported = new LinkedHashMap<>();
    private final List<Pass> passes = new ArrayList<>();

    public UrdRenderGraph resource(String name) {
        requireName(name, "resource");
        if (resources.putIfAbsent(name, Boolean.TRUE) != null) {
            throw new UrdValidationException("duplicate graph resource: " + name);
        }
        return this;
    }

    public UrdRenderGraph importedResource(String name) {
        resource(name);
        imported.put(name, Boolean.TRUE);
        return this;
    }

    public UrdRenderGraph pass(String name, List<String> reads, List<String> writes) {
        requireName(name, "pass");
        List<String> readCopy = List.copyOf(reads == null ? List.of() : reads);
        List<String> writeCopy = List.copyOf(writes == null ? List.of() : writes);
        for (String resource : readCopy) requireResource(resource);
        for (String resource : writeCopy) requireResource(resource);
        for (String resource : writeCopy) {
            if (readCopy.contains(resource)) {
                throw new UrdValidationException("pass reads and writes resource: " + resource);
            }
        }
        passes.add(new Pass(name, readCopy, writeCopy));
        return this;
    }

    public List<Pass> compile() {
        Map<String, Integer> lastWriter = new LinkedHashMap<>();
        List<Pass> ordered = new ArrayList<>();
        for (Pass pass : passes) {
            for (String read : pass.reads()) {
                if (!lastWriter.containsKey(read) && !imported.containsKey(read)) {
                    throw new UrdValidationException("read of uninitialized graph resource: " + read);
                }
            }
            for (String write : pass.writes()) lastWriter.put(write, ordered.size());
            ordered.add(pass);
        }
        return List.copyOf(ordered);
    }

    private void requireResource(String name) {
        if (!resources.containsKey(name)) throw new UrdValidationException("unknown graph resource: " + name);
    }

    private static void requireName(String value, String kind) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("blank graph " + kind + " name");
    }

    public record Pass(String name, List<String> reads, List<String> writes) {
        public Pass {
            reads = List.copyOf(reads);
            writes = List.copyOf(writes);
        }
    }
}
