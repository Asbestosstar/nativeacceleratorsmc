package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Enumerates wrapper-authoritative conventional variants without compiling or claiming support. */
public final class ShaderpackConventionalVariantScanner {
    private static boolean isSharedFullscreenProgram(String program) {
        return program.equals("final") || program.matches("composite[0-9]*");
    }

    public List<ShaderpackStageVariant> scan(ShaderPackSource source) {
        if (source == null) throw new IllegalArgumentException("source is null");
        String root = source.list().stream().anyMatch(value -> value.startsWith("shaders/")) ? "shaders/" : "";
        Map<String, List<ShaderpackStageVariant>> grouped = new LinkedHashMap<>();
        for (String path : source.list().stream().sorted().toList()) {
            if (!path.startsWith(root)) continue;
            String relative = path.substring(root.length());
            String lower = relative.toLowerCase(Locale.ROOT);
            String stage = lower.endsWith(".vsh") ? "vertex" : lower.endsWith(".fsh") ? "fragment" : "";
            if (stage.isEmpty()) continue;
            String[] pieces = lower.split("/");
            ShaderpackDimension dimension = ShaderpackDimension.fromPath(relative);
            String file = pieces[pieces.length - 1];
            String program = file.substring(0, file.lastIndexOf('.'));
            if (dimension == ShaderpackDimension.UNKNOWN
                    && (pieces.length != 1 || !isSharedFullscreenProgram(program))) continue;
            String normalizedWrapper = root + relative.replace('\\', '/');
            ShaderpackStageVariant variant = new ShaderpackStageVariant(program, dimension, stage,
                    normalizedWrapper, dimension == ShaderpackDimension.UNKNOWN
                            ? Map.of() : Map.of(dimension.define(), "1"), List.of());
            grouped.computeIfAbsent(variant.key(), ignored -> new ArrayList<>()).add(variant);
        }
        return grouped.values().stream().flatMap(List::stream)
                .sorted(Comparator.comparing(ShaderpackStageVariant::key)).toList();
    }
}
