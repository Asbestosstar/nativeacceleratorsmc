package com.asbestosstar.nativeaccelerator.client;

import net.minecraft.resources.Identifier;

import java.util.concurrent.ConcurrentHashMap;

/** Reload-scoped canonicalization for the thousands of repeated model/texture identifier strings. */
public final class IdentifierInterner {
    private static final ConcurrentHashMap<String, Identifier> IDENTIFIERS = new ConcurrentHashMap<>(8192);

    private IdentifierInterner() {}

    public static Identifier parse(String text) {
        Identifier existing = IDENTIFIERS.get(text);
        if (existing != null) {
            ModelPipelineProfiler.addCount("identifier.interner.hit", 1);
            return existing;
        }
        Identifier parsed = Identifier.parse(text);
        Identifier raced = IDENTIFIERS.putIfAbsent(text, parsed);
        ModelPipelineProfiler.addCount(raced == null ? "identifier.interner.miss" : "identifier.interner.race-hit", 1);
        return raced == null ? parsed : raced;
    }

    public static void clear() {
        IDENTIFIERS.clear();
    }
}

