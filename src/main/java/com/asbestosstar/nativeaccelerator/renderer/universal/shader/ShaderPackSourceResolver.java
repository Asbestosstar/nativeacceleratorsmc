package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves simple GLSL-style quoted/angled includes without touching the host filesystem. */
public final class ShaderPackSourceResolver {
    private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s*[<\\\"]([^>\\\"]+)[>\\\"]\\s*$");
    private final ShaderPackSource source;
    private final int maxDepth;
    private final List<ShaderPackDiagnostic> diagnostics = new ArrayList<>();

    public ShaderPackSourceResolver(ShaderPackSource source) { this(source, ShaderPackReadLimits.DEFAULT.maxIncludeDepth()); }
    public ShaderPackSourceResolver(ShaderPackSource source, int maxDepth) {
        if (source == null || maxDepth <= 0) throw new IllegalArgumentException("source and maxDepth are required");
        this.source = source; this.maxDepth = maxDepth;
    }

    public String resolve(String path) {
        String normalized = ShaderPackSourceProvider.normalize(path);
        if (normalized == null) { fail(ShaderPackDiagnostic.Kind.INVALID_PATH, path, "unsafe root include"); return null; }
        return expand(normalized, new ArrayDeque<>(), 0);
    }

    public List<ShaderPackDiagnostic> diagnostics() { return List.copyOf(diagnostics); }

    private String expand(String path, ArrayDeque<String> stack, int depth) {
        if (depth > maxDepth) { fail(ShaderPackDiagnostic.Kind.LIMIT_EXCEEDED, path, "include depth exceeds " + maxDepth); return null; }
        if (!stack.add(path)) { fail(ShaderPackDiagnostic.Kind.INCLUDE_ERROR, path, "include cycle: " + stack); return null; }
        String text = source.readText(path);
        if (text == null) { fail(ShaderPackDiagnostic.Kind.INCLUDE_ERROR, path, "included source is absent"); stack.removeLast(); return null; }
        StringBuilder out = new StringBuilder(text.length());
        String[] lines = text.split("\\R", -1);
        for (String line : lines) {
            Matcher matcher = INCLUDE.matcher(line);
            if (!matcher.matches()) { out.append(line).append('\n'); continue; }
            String child = ShaderPackSourceProvider.normalize(resolveRelative(path, matcher.group(1)));
            if (child == null) { fail(ShaderPackDiagnostic.Kind.INVALID_PATH, matcher.group(1), "unsafe include path"); stack.removeLast(); return null; }
            String expanded = expand(child, stack, depth + 1);
            if (expanded == null) { stack.removeLast(); return null; }
            out.append(expanded);
        }
        stack.removeLast();
        return out.toString();
    }

    private static String resolveRelative(String parent, String include) {
        int slash = parent.lastIndexOf('/');
        return slash < 0 ? include : parent.substring(0, slash + 1) + include;
    }

    private void fail(ShaderPackDiagnostic.Kind kind, String path, String detail) {
        diagnostics.add(new ShaderPackDiagnostic(kind, path, detail));
    }
}
