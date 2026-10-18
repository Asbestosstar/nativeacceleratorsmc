package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded, loader-neutral parser for the commonly used typed subset of OptiFine/Iris
 * shaders.properties metadata. It does not interpret arbitrary Java or shader expressions.
 */
public final class ShaderPropertiesParser {
    public static final Limits DEFAULT_LIMITS = new Limits(1 << 20, 8192, 256, 8192, 4096, 64);
    private static final Pattern IDENT = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final Pattern COMPARISON = Pattern.compile("([A-Za-z0-9_.-]{1,128})\\s*(==|!=)\\s*(true|false)", Pattern.CASE_INSENSITIVE);
    private ShaderPropertiesParser() {}

    public record Limits(int maxChars, int maxLines, int maxProfiles, int maxAssignments,
                         int maxExpressionChars, int maxExpressionDepth) {
        public Limits {
            if (maxChars < 1 || maxLines < 1 || maxProfiles < 1 || maxAssignments < 1
                    || maxExpressionChars < 1 || maxExpressionDepth < 1) throw new IllegalArgumentException("limits must be positive");
        }
    }

    /** Parse profiles in the iteration order supplied by the set; earlier assignments win. */
    public static ShaderPropertiesProfile parse(String input, Set<String> requestedProfiles) {
        if (requestedProfiles == null) throw new IllegalArgumentException("requestedProfiles is null");
        return parse(input, new ArrayList<>(requestedProfiles), Map.of(), DEFAULT_LIMITS);
    }

    /** Parse and resolve requested profiles in caller order; earlier assignments win. */
    public static ShaderPropertiesProfile parse(String input, List<String> requestedProfiles) {
        return parse(input, requestedProfiles, Map.of(), DEFAULT_LIMITS);
    }

    /** Parse profiles and apply selected boolean option values to feature expressions. */
    public static ShaderPropertiesProfile parse(String input, List<String> requestedProfiles,
                                                   Map<String, String> selectedOptions) {
        return parse(input, requestedProfiles, selectedOptions, DEFAULT_LIMITS);
    }

    public static ShaderPropertiesProfile parse(String input, Set<String> requestedProfiles, Limits limits) {
        if (requestedProfiles == null) throw new IllegalArgumentException("requestedProfiles is null");
        return parse(input, new ArrayList<>(requestedProfiles), Map.of(), limits);
    }

    public static ShaderPropertiesProfile parse(String input, List<String> requestedProfiles,
                                                   Map<String, String> selectedOptions, Limits limits) {
        if (input == null) throw new IllegalArgumentException("input is null");
        if (limits == null) throw new IllegalArgumentException("limits is null");
        if (requestedProfiles == null) throw new IllegalArgumentException("requestedProfiles is null");
        if (selectedOptions == null) throw new IllegalArgumentException("selectedOptions is null");
        if (input.length() > limits.maxChars()) throw new IllegalArgumentException("shaders.properties exceeds character limit");
        String[] lines = input.split("\\R", -1);
        if (lines.length > limits.maxLines()) throw new IllegalArgumentException("shaders.properties exceeds line limit");

        Map<String, String> props = new LinkedHashMap<>();
        List<String> diagnostics = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            int split = delimiter(line);
            if (split <= 0) { diagnostics.add("line " + (i + 1) + ": ignored malformed property"); continue; }
            String key = line.substring(0, split).trim();
            String value = line.substring(split + 1).trim();
            if (key.length() > 128 || value.length() > limits.maxExpressionChars()) {
                diagnostics.add("line " + (i + 1) + ": property exceeds bound"); continue;
            }
            props.put(key, value);
        }

        Map<String, List<Assignment>> profileDefs = new LinkedHashMap<>();
        Map<String, Boolean> features = new LinkedHashMap<>();
        Map<String, String> programExprs = new LinkedHashMap<>();
        Map<String, List<String>> screens = new LinkedHashMap<>();
        Map<String, ShaderPropertiesProfile.ShaderOption> options = new LinkedHashMap<>();
        Set<String> sliders = new LinkedHashSet<>();
        int assignmentCount = 0;
        for (var entry : props.entrySet()) {
            String key = entry.getKey(), value = entry.getValue();
            if (key.startsWith("profile.")) {
                String profile = key.substring(8);
                if (!IDENT.matcher(profile).matches()) { diagnostics.add("invalid profile name: " + profile); continue; }
                List<Assignment> parsed = parseAssignments(value, diagnostics, key);
                assignmentCount += parsed.size();
                profileDefs.put(profile, parsed);
            } else if (key.startsWith("program.")) {
                String name = key.substring(8);
                if (name.endsWith(".enabled")) name = name.substring(0, name.length() - ".enabled".length());
                if (IDENT.matcher(name).matches()) programExprs.put(name, value);
                else diagnostics.add("invalid program name: " + name);
            } else if (key.startsWith("screen.")) {
                String screen = key.substring(7);
                if (IDENT.matcher(screen).matches()) screens.put(screen, tokens(value));
                else diagnostics.add("invalid screen name: " + screen);
            } else if (key.equals("screen")) {
                screens.put("", tokens(value));
            } else if (key.startsWith("option.")) {
                String name = key.substring(7);
                if (IDENT.matcher(name).matches()) options.put(name, new ShaderPropertiesProfile.ShaderOption(
                        tokens(value), "", name));
                else diagnostics.add("invalid option name: " + name);
            } else if (key.startsWith("value.")) {
                String name = key.substring(6);
                if (IDENT.matcher(name).matches()) {
                    ShaderPropertiesProfile.ShaderOption old = options.get(name);
                    options.put(name, new ShaderPropertiesProfile.ShaderOption(
                            old == null ? List.of() : old.values(), value, old == null ? name : old.displayName()));
                } else diagnostics.add("invalid option name: " + name);
            } else if (key.startsWith("name.")) {
                String name = key.substring(5);
                if (IDENT.matcher(name).matches()) {
                    ShaderPropertiesProfile.ShaderOption old = options.get(name);
                    options.put(name, new ShaderPropertiesProfile.ShaderOption(
                            old == null ? List.of() : old.values(), old == null ? "" : old.defaultValue(), value));
                }
            } else if (key.equals("sliders")) {
                sliders.addAll(tokens(value));
            } else if (key.startsWith("sliders.")) {
                sliders.addAll(tokens(value));
            } else if (looksLikeFeature(key)) {
                Boolean bool = parseBoolean(value);
                if (bool != null) features.put(key, bool);
                else diagnostics.add("invalid boolean feature assignment: " + key);
            }
        }
        if (assignmentCount > limits.maxAssignments()) throw new IllegalArgumentException("profile assignments exceed limit");
        if (profileDefs.size() > limits.maxProfiles()) throw new IllegalArgumentException("profile count exceeds limit");

        if (requestedProfiles.size() > limits.maxProfiles()) throw new IllegalArgumentException("requested profile count exceeds limit");
        List<String> selected = new ArrayList<>();
        Set<String> selectedOnce = new LinkedHashSet<>();
        for (String requested : requestedProfiles) {
            if (!selectedOnce.add(requested)) continue;
            if (!profileDefs.containsKey(requested)) {
                diagnostics.add("unknown requested profile: " + requested);
                continue;
            }
            selected.add(requested);
        }
        Map<String, Boolean> resolvedFeatures = new LinkedHashMap<>(features);
        Set<String> active = new LinkedHashSet<>();
        Set<String> done = new LinkedHashSet<>();
        List<String> resolutionOrder = new ArrayList<>(selected);
        java.util.Collections.reverse(resolutionOrder);
        for (String name : resolutionOrder) resolveProfile(name, profileDefs, resolvedFeatures, active, done, diagnostics, limits);
        for (var entry : selectedOptions.entrySet()) {
            Boolean value = parseBoolean(entry.getValue());
            if (value != null) resolvedFeatures.put(entry.getKey(), value);
        }

        Map<String, Boolean> programs = new LinkedHashMap<>();
        programExprs.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            try { programs.put(entry.getKey(), new Expr(entry.getValue(), resolvedFeatures, limits.maxExpressionDepth(), limits.maxExpressionChars()).parse()); }
            catch (IllegalArgumentException e) { diagnostics.add("program." + entry.getKey() + ": " + e.getMessage()); }
        });
        return new ShaderPropertiesProfile(resolvedFeatures, programs, options, screens, sliders, selected, diagnostics);
    }

    private static int delimiter(String line) {
        int equals = line.indexOf('='), colon = line.indexOf(':');
        if (equals < 0) return colon;
        if (colon < 0) return equals;
        return Math.min(equals, colon);
    }

    private static boolean looksLikeFeature(String key) {
        return IDENT.matcher(key).matches() && !key.contains("/");
    }

    private static Boolean parseBoolean(String value) {
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        return null;
    }

    private static List<String> tokens(String value) {
        List<String> result = new ArrayList<>();
        for (String token : value.split("[ ,]+")) if (!token.isBlank()) result.add(token.trim());
        return List.copyOf(result);
    }

    private static List<Assignment> parseAssignments(String value, List<String> diagnostics, String key) {
        List<Assignment> result = new ArrayList<>();
        for (String raw : value.split("[ ,]+")) {
            if (raw.isBlank()) continue;
            int eq = raw.indexOf('=');
            if (eq < 0) {
                if (IDENT.matcher(raw).matches()) result.add(new Assignment(raw, null));
                else diagnostics.add(key + ": ignored assignment " + raw);
                continue;
            }
            if (eq == 0 || eq == raw.length() - 1) { diagnostics.add(key + ": ignored assignment " + raw); continue; }
            String name = raw.substring(0, eq);
            Boolean bool = parseBoolean(raw.substring(eq + 1));
            if (!IDENT.matcher(name).matches() || bool == null) { diagnostics.add(key + ": ignored assignment " + raw); continue; }
            result.add(new Assignment(name, bool));
        }
        return result;
    }

    private static void resolveProfile(String name, Map<String, List<Assignment>> definitions,
            Map<String, Boolean> features, Set<String> active, Set<String> done, List<String> diagnostics, Limits limits) {
        if (done.contains(name)) return;
        if (!active.add(name)) { diagnostics.add("profile inheritance cycle at " + name); return; }
        List<Assignment> values = definitions.getOrDefault(name, List.of());
        for (Assignment assignment : values) {
            if (assignment.value == null) {
                if (definitions.containsKey(assignment.name)) resolveProfile(assignment.name, definitions, features, active, done, diagnostics, limits);
                else diagnostics.add("profile." + name + ": unknown parent " + assignment.name);
            } else features.put(assignment.name, assignment.value);
        }
        active.remove(name);
        done.add(name);
    }

    private record Assignment(String name, Boolean value) {}

    private static final class Expr {
        private final String text;
        private final Map<String, Boolean> features;
        private final int maxDepth;
        private int pos;
        Expr(String text, Map<String, Boolean> features, int maxDepth, int maxChars) {
            this.text = text;
            this.features = features;
            this.maxDepth = maxDepth;
            if (text.length() > maxChars) throw new IllegalArgumentException("expression exceeds limit");
        }
        boolean parse() { boolean value = or(0); whitespace(); if (pos != text.length()) throw error("unexpected token"); return value; }
        private boolean or(int depth) { check(depth); boolean value = and(depth + 1); while (take("||")) { boolean rhs = and(depth + 1); value = value || rhs; } return value; }
        private boolean and(int depth) { check(depth); boolean value = unary(depth + 1); while (take("&&")) { boolean rhs = unary(depth + 1); value = value && rhs; } return value; }
        private boolean unary(int depth) {
            check(depth);
            if (take("!")) return !unary(depth + 1);
            if (take("(")) { boolean value = or(depth + 1); if (!take(")")) throw error("expected )"); return value; }
            int start = pos;
            while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || "_.-".indexOf(text.charAt(pos)) >= 0)) pos++;
            if (start == pos) throw error("expected feature identifier");
            String key = text.substring(start, pos);
            Boolean expected = true;
            whitespace();
            if (take("==")) expected = readBoolean();
            else if (take("!=")) expected = !readBoolean();
            Boolean actual = features.get(key);
            if (actual == null) throw error("unknown feature " + key);
            return actual.equals(expected);
        }
        private Boolean readBoolean() {
            whitespace();
            if (takeWord("true")) return true;
            if (takeWord("false")) return false;
            throw error("expected boolean literal");
        }
        private boolean takeWord(String word) { whitespace(); if (text.regionMatches(true, pos, word, 0, word.length())) { pos += word.length(); return true; } return false; }
        private boolean take(String token) { whitespace(); if (text.startsWith(token, pos)) { pos += token.length(); return true; } return false; }
        private void whitespace() { while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++; }
        private void check(int depth) { if (depth > maxDepth) throw error("expression nesting exceeds limit"); }
        private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " at " + pos); }
    }
}
