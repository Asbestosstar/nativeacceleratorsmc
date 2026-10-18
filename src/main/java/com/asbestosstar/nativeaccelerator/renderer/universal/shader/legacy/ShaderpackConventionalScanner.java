package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Clean-room scanner for the public conventional shaderpack layout.
 *
 * <p>This class inventories sources and properties only. It never treats file-name detection as
 * compilation or runtime compatibility evidence.</p>
 */
public final class ShaderpackConventionalScanner {
    private static final Set<String> STAGES = Set.of(".vsh", ".fsh", ".gsh", ".tes", ".tcs", ".csh", ".glsl");

    public ShaderpackConventionalProfile scan(ShaderPackSource source) {
        if (source == null) throw new IllegalArgumentException("source is null");
        List<String> names = source.list();
        String root = root(names);
        String prefix = root.isEmpty() ? "" : root + "/";
        Map<String, String> properties = properties(source, prefix);
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        Set<String> unsupported = new TreeSet<>();
        List<String> diagnostics = new ArrayList<>();
        boolean conventional = !properties.isEmpty();
        boolean nativeManifest = source.read("nativeaccelerator.shaderpack.json").isPresent()
                || (!prefix.isEmpty() && source.read(prefix + "nativeaccelerator.shaderpack.json").isPresent());

        for (String name : names) {
            if (!name.startsWith(prefix)) continue;
            String relative = name.substring(prefix.length());
            String lower = relative.toLowerCase(Locale.ROOT);
            int slash = lower.lastIndexOf('/');
            String file = slash < 0 ? lower : lower.substring(slash + 1);
            String extension = extension(file);
            if (STAGES.contains(extension)) {
                conventional |= file.startsWith("gbuffers_") || file.startsWith("shadow")
                        || file.startsWith("deferred") || file.startsWith("composite") || file.startsWith("final")
                        || lower.startsWith("program/");
                String stem = file.substring(0, file.length() - extension.length());
                if (extension.equals(".glsl") && !lower.contains("program/")) continue;
                String program = lower.contains("program/")
                        ? "program/" + stem
                        : lower.substring(0, Math.max(0, lower.lastIndexOf('/') + 1)) + stem;
                grouped.computeIfAbsent(program, ignored -> new ArrayList<>()).add(name);
                String text = source.readText(name);
                if (text != null && (text.contains("DRAWBUFFERS") || text.contains("RENDERTARGETS"))) {
                    // The declarations are recorded as a requirement until a target adapter exists.
                    unsupported.add("multiple-render-targets");
                }
                if (text != null && (text.contains("imageStore") || text.matches("(?s).*\\bbuffer\\s+.*")))
                    unsupported.add("images-or-storage-buffers");
                if (text != null && text.contains("#include")) {
                    // Include resolution is supported by the bounded preprocessor; keep this fact in
                    // the inventory so preparation can include the dependency closure in its identity.
                }
            } else if (lower.contains("/program/") && !file.equals("shaders.properties")) {
                diagnostics.add("unrecognized program source: " + name);
            }
        }
        List<ShaderpackProgram> programs = grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ShaderpackProgram(entry.getKey(), entry.getValue().stream().sorted().toList()))
                .toList();
        if (conventional && programs.isEmpty()) diagnostics.add("conventional metadata has no stage programs");
        if (programs.stream().anyMatch(value -> value.hasStage(".gsh") || value.hasStage(".tes")
                || value.hasStage(".tcs") || value.hasStage(".csh"))) {
            unsupported.add("non-vertex-fragment-stages");
        }
        if (programs.stream().anyMatch(ShaderpackProgram::isWorldVariant)) {
            unsupported.add("world-stage-passes");
        }
        ShaderpackFormat format = nativeManifest ? ShaderpackFormat.NATIVE_ACCELERATOR_MANIFEST
                : conventional ? (properties.keySet().stream().anyMatch(key -> key.startsWith("iris."))
                        ? ShaderpackFormat.IRIS_COMPATIBLE : ShaderpackFormat.OPTIFINE_COMPATIBLE)
                : ShaderpackFormat.UNKNOWN;
        return new ShaderpackConventionalProfile(format, root, properties, programs, unsupported, diagnostics);
    }

    private static Map<String, String> properties(ShaderPackSource source, String prefix) {
        String text = source.readText(prefix + "shaders.properties");
        if (text == null) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : text.split("\\R", -1)) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            int separator = value.indexOf('=');
            if (separator <= 0) continue;
            result.putIfAbsent(value.substring(0, separator).trim(), value.substring(separator + 1).trim());
        }
        return result;
    }

    private static String root(List<String> names) {
        if (names.contains("shaders/shaders.properties") || names.stream().anyMatch(value -> value.startsWith("shaders/"))) return "shaders";
        if (names.contains("shaders.properties") || names.stream().anyMatch(value -> value.startsWith("program/"))) return "";
        return names.stream().filter(value -> value.endsWith("/shaders.properties")).findFirst()
                .map(value -> value.substring(0, value.length() - "/shaders.properties".length())).orElse("");
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }
}
