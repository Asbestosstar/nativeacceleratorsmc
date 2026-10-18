package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded GLSL preprocessing for shaderpack source snapshots.
 *
 * <p>This class owns source inclusion, conditional selection, macro expansion, dependency tracking,
 * and source provenance. It deliberately does not parse GLSL or manufacture a backend module; the
 * selected real compiler remains authoritative for language semantics.</p>
 */
public final class GlslPreprocessor {
    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#\\s*([A-Za-z_][A-Za-z0-9_]*)(?:\\s+(.*?))?\\s*$");
    private static final Pattern DEFINE = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)(?:\\(([^)]*)\\))?\\s*(.*)");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b");
    private static final Pattern INCLUDE = Pattern.compile("[<\\\"]([^>\\\"]+)[>\\\"]");
    private static final Pattern INTEGER = Pattern.compile("[-+]?\\d+");

    public interface SourceResolver {
        Optional<String> read(String normalizedPath);
    }

    public record Limits(long maxExpandedBytes, int maxIncludeDepth, int maxIncludes, int maxMacroExpansions) {
        public static final Limits DEFAULT = new Limits(16L * 1024 * 1024, 64, 1024, 100_000);
        public Limits {
            if (maxExpandedBytes <= 0 || maxIncludeDepth <= 0 || maxIncludes <= 0 || maxMacroExpansions <= 0) {
                throw new IllegalArgumentException("preprocessor limits must be positive");
            }
        }
    }

    public record SourceLocation(String sourceName, int line) {
        public SourceLocation {
            sourceName = sourceName == null || sourceName.isBlank() ? "<unknown>" : sourceName;
            line = Math.max(1, line);
        }
    }

    public record Dependency(String path, String contentHash) {
        public Dependency {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("dependency path is blank");
            if (contentHash == null || contentHash.isBlank()) throw new IllegalArgumentException("dependency hash is blank");
        }
    }

    public record Result(String source, List<Dependency> dependencies, List<SourceLocation> sourceMap,
                         List<ShaderDiagnostic> diagnostics) {
        public Result {
            source = source == null ? "" : source;
            dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
            sourceMap = List.copyOf(sourceMap == null ? List.of() : sourceMap);
            diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
        }
        public boolean successful() {
            return diagnostics.stream().noneMatch(d -> d.severity() == ShaderDiagnostic.Severity.ERROR);
        }
    }

    private final SourceResolver resolver;
    private final Limits limits;
    private final Map<String, Macro> macros = new LinkedHashMap<>();
    private final List<Dependency> dependencies = new ArrayList<>();
    private final List<SourceLocation> sourceMap = new ArrayList<>();
    private final List<ShaderDiagnostic> diagnostics = new ArrayList<>();
    private final Set<String> included = new HashSet<>();
    private final ArrayDeque<String> stack = new ArrayDeque<>();
    private long expandedBytes;
    private int includeCount;
    private int macroExpansions;

    public GlslPreprocessor(SourceResolver resolver) {
        this(resolver, Limits.DEFAULT);
    }

    public GlslPreprocessor(SourceResolver resolver, Limits limits) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public Result process(String rootPath, Map<String, String> initialDefines) {
        reset();
        String root = normalize(rootPath);
        if (root == null) {
            error(rootPath, 1, "invalid shader source path");
            return result();
        }
        if (initialDefines != null) {
            initialDefines.forEach((key, value) -> {
                if (key != null && IDENTIFIER.matcher(key).matches()) macros.put(key, new Macro(key, List.of(), value == null ? "1" : value));
            });
        }
        StringBuilder output = new StringBuilder();
        expand(root, output, 0, true);
        return result(output);
    }

    public Result process(String rootPath) {
        return process(rootPath, Map.of());
    }

    private void expand(String path, StringBuilder output, int depth, boolean root) {
        if (depth > limits.maxIncludeDepth()) {
            error(path, 1, "include depth exceeds " + limits.maxIncludeDepth());
            return;
        }
        if (includeCount++ >= limits.maxIncludes()) {
            error(path, 1, "include count exceeds " + limits.maxIncludes());
            return;
        }
        if (!stack.add(path)) {
            error(path, 1, "include cycle: " + stack);
            return;
        }
        Optional<String> loaded = resolver.read(path);
        if (loaded.isEmpty()) {
            error(path, 1, "shader source is absent");
            stack.removeLast();
            return;
        }
        String text = loaded.get();
        dependencies.removeIf(value -> value.path().equals(path));
        dependencies.add(new Dependency(path, sha256(text)));
        included.add(path);
        if (!root) appendLine(output, "#line 1 \"" + escape(path) + "\"", new SourceLocation(path, 1));
        Conditional conditional = new Conditional();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNumber = i + 1;
            String line = lines[i];
            Matcher matcher = DIRECTIVE.matcher(line);
            if (matcher.matches()) {
                String directive = matcher.group(1);
                String argument = matcher.group(2) == null ? "" : matcher.group(2).trim();
                if (handleConditional(directive, argument, conditional, path, lineNumber)) continue;
                if (!conditional.active()) continue;
                if (directive.equals("include")) {
                    Matcher include = INCLUDE.matcher(argument);
                    if (!include.find()) { error(path, lineNumber, "malformed #include"); continue; }
                    String child = resolveInclude(path, include.group(1));
                    if (child == null) { error(path, lineNumber, "include escapes shaderpack root"); continue; }
                    expand(child, output, depth + 1, false);
                    appendLine(output, "#line " + (lineNumber + 1) + " \"" + escape(path) + "\"",
                            new SourceLocation(path, lineNumber));
                    continue;
                }
                if (directive.equals("define")) { define(argument, path, lineNumber); continue; }
                if (directive.equals("undef")) { macros.remove(argument.trim()); continue; }
                if (directive.equals("version") || directive.equals("extension") || directive.equals("line")) {
                    appendLine(output, line, new SourceLocation(path, lineNumber));
                }
                continue;
            }
            if (conditional.active()) {
                String expanded = expandMacros(line, path, lineNumber);
                appendLine(output, expanded, new SourceLocation(path, lineNumber));
            }
        }
        if (!conditional.empty()) error(path, lines.length, "unterminated conditional directive");
        stack.removeLast();
    }

    private boolean handleConditional(String directive, String argument, Conditional conditional,
                                      String path, int line) {
        switch (directive) {
            case "if" -> conditional.begin(condition(argument, path, line), conditional.active());
            case "ifdef" -> conditional.begin(macros.containsKey(argument), conditional.active());
            case "ifndef" -> conditional.begin(!macros.containsKey(argument), conditional.active());
            case "elif" -> conditional.elif(condition(argument, path, line));
            case "else" -> conditional.otherwise();
            case "endif" -> conditional.end();
            default -> { return false; }
        }
        if (conditional.invalid()) error(path, line, "unbalanced conditional directive: #" + directive);
        return true;
    }

    private void define(String argument, String path, int line) {
        Matcher matcher = DEFINE.matcher(argument);
        if (!matcher.matches()) { error(path, line, "malformed #define"); return; }
        String name = matcher.group(1);
        String parameters = matcher.group(2);
        List<String> args = parameters == null || parameters.isBlank() ? List.of()
                : List.of(parameters.split(",")).stream().map(String::trim).toList();
        if (args.stream().anyMatch(value -> !IDENTIFIER.matcher(value).matches())) {
            error(path, line, "invalid macro parameter"); return;
        }
        macros.put(name, new Macro(name, args, matcher.group(3) == null ? "" : matcher.group(3)));
    }

    private String expandMacros(String line, String path, int sourceLine) {
        String value = line;
        for (int pass = 0; pass < 32; pass++) {
            Matcher identifiers = IDENTIFIER.matcher(value);
            StringBuffer replacement = new StringBuffer();
            boolean changed = false;
            while (identifiers.find()) {
                Macro macro = macros.get(identifiers.group());
                if (macro == null || macro.name().equals(identifiers.group()) && macro.parameters().isEmpty()
                        && macro.body().contains(identifiers.group())) {
                    continue;
                }
                String replacementText = macro.body();
                if (!macro.parameters().isEmpty()) {
                    int open = identifiers.end();
                    while (open < value.length() && Character.isWhitespace(value.charAt(open))) open++;
                    if (open >= value.length() || value.charAt(open) != '(') continue;
                    int close = matchingParen(value, open);
                    if (close < 0) { error(path, sourceLine, "unterminated function macro invocation"); return value; }
                    List<String> actual = splitArguments(value.substring(open + 1, close));
                    if (actual.size() != macro.parameters().size()) {
                        error(path, sourceLine, "macro " + macro.name() + " expects " + macro.parameters().size() + " arguments");
                        continue;
                    }
                    for (int i = 0; i < actual.size(); i++) replacementText = replacementText.replace(macro.parameters().get(i), actual.get(i));
                }
                identifiers.appendReplacement(replacement, Matcher.quoteReplacement(replacementText));
                changed = true;
                if (++macroExpansions > limits.maxMacroExpansions()) {
                    error(path, sourceLine, "macro expansion limit exceeded");
                    return value;
                }
            }
            identifiers.appendTail(replacement);
            if (!changed) return value;
            value = replacement.toString();
        }
        error(path, sourceLine, "macro expansion did not converge");
        return value;
    }

    private boolean condition(String expression, String path, int line) {
        try {
            return new ConditionParser(expression == null ? "" : expression).parse() != 0;
        } catch (IllegalArgumentException ex) {
            error(path, line, "invalid conditional expression: " + ex.getMessage());
            return false;
        }
    }

    private final class ConditionParser {
        private final String input;
        private int cursor;

        private ConditionParser(String input) { this.input = input; }

        private int parse() {
            int value = or();
            skip();
            if (cursor != input.length()) throw new IllegalArgumentException("unexpected token");
            return value;
        }

        private int or() {
            int value = and();
            while (take("||")) { int right = and(); value = truth(value) != 0 || truth(right) != 0 ? 1 : 0; }
            return value;
        }

        private int and() {
            int value = bitOr();
            while (take("&&")) { int right = bitOr(); value = truth(value) != 0 && truth(right) != 0 ? 1 : 0; }
            return value;
        }

        private int bitOr() {
            int value = bitXor();
            while (takeSingle("|", "||")) value |= bitXor();
            return value;
        }

        private int bitXor() {
            int value = bitAnd();
            while (take("^")) value ^= bitAnd();
            return value;
        }

        private int bitAnd() {
            int value = equality();
            while (takeSingle("&", "&&")) value &= equality();
            return value;
        }

        private int equality() {
            int value = relational();
            while (true) {
                if (take("==")) value = value == relational() ? 1 : 0;
                else if (take("!=")) value = value != relational() ? 1 : 0;
                else return value;
            }
        }

        private int relational() {
            int value = additive();
            while (true) {
                if (take("<=")) value = value <= additive() ? 1 : 0;
                else if (take(">=")) value = value >= additive() ? 1 : 0;
                else if (take("<")) value = value < additive() ? 1 : 0;
                else if (take(">")) value = value > additive() ? 1 : 0;
                else return value;
            }
        }

        private int additive() {
            int value = multiplicative();
            while (true) {
                if (take("+")) value += multiplicative();
                else if (take("-")) value -= multiplicative();
                else return value;
            }
        }

        private int multiplicative() {
            int value = unary();
            while (true) {
                if (take("*")) value *= unary();
                else if (take("/")) { int divisor = unary(); if (divisor == 0) throw new IllegalArgumentException("division by zero"); value /= divisor; }
                else if (take("%")) { int divisor = unary(); if (divisor == 0) throw new IllegalArgumentException("division by zero"); value %= divisor; }
                else return value;
            }
        }

        private int unary() {
            if (take("!")) return truth(unary()) == 0 ? 1 : 0;
            if (take("~")) return ~unary();
            if (take("+")) return unary();
            if (take("-")) return -unary();
            return primary();
        }

        private int primary() {
            skip();
            if (take("(")) { int value = or(); require(")"); return value; }
            if (takeWord("defined")) {
                boolean parenthesized = take("(");
                String name = identifier();
                if (parenthesized) require(")");
                return macros.containsKey(name) ? 1 : 0;
            }
            if (cursor < input.length() && (Character.isDigit(input.charAt(cursor)))) return number();
            String name = identifier();
            Macro macro = macros.get(name);
            return macro == null ? 0 : integer(macro.body());
        }

        private int number() {
            int start = cursor;
            while (cursor < input.length() && (Character.isDigit(input.charAt(cursor)) ||
                    Character.toLowerCase(input.charAt(cursor)) >= 'a' && Character.toLowerCase(input.charAt(cursor)) <= 'f' ||
                    input.charAt(cursor) == 'x' || input.charAt(cursor) == 'X')) cursor++;
            String value = input.substring(start, cursor);
            try { return value.startsWith("0x") || value.startsWith("0X") ? Integer.parseUnsignedInt(value.substring(2), 16) : Integer.parseInt(value); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("invalid integer " + value); }
        }

        private String identifier() {
            skip();
            int start = cursor;
            if (cursor >= input.length() || !(Character.isLetter(input.charAt(cursor)) || input.charAt(cursor) == '_')) throw new IllegalArgumentException("identifier expected");
            cursor++;
            while (cursor < input.length() && (Character.isLetterOrDigit(input.charAt(cursor)) || input.charAt(cursor) == '_')) cursor++;
            return input.substring(start, cursor);
        }

        private boolean takeWord(String word) {
            skip(); int end = cursor + word.length();
            if (end <= input.length() && input.regionMatches(cursor, word, 0, word.length()) &&
                    (end == input.length() || !Character.isLetterOrDigit(input.charAt(end)) && input.charAt(end) != '_')) { cursor = end; return true; }
            return false;
        }

        private boolean take(String token) { skip(); if (input.startsWith(token, cursor)) { cursor += token.length(); return true; } return false; }
        private boolean takeSingle(String token, String longer) { skip(); if (input.startsWith(longer, cursor)) return false; return take(token); }
        private void require(String token) { if (!take(token)) throw new IllegalArgumentException("expected " + token); }
        private void skip() { while (cursor < input.length() && Character.isWhitespace(input.charAt(cursor))) cursor++; }
        private int truth(int value) { return value == 0 ? 0 : 1; }
    }

    private static int integer(String value) {
        try { return Integer.decode(value.trim()); } catch (NumberFormatException ignored) { return value.isBlank() ? 0 : 1; }
    }

    private void appendLine(StringBuilder output, String line, SourceLocation location) {
        long added = line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
        if (expandedBytes > limits.maxExpandedBytes() - added) {
            error(location.sourceName(), location.line(), "expanded source exceeds configured limit");
            return;
        }
        expandedBytes += added;
        output.append(line).append('\n');
        sourceMap.add(location);
    }

    private Result result() { return new Result("", dependencies, sourceMap, diagnostics); }
    private Result result(StringBuilder output) { return new Result(output.toString(), dependencies, sourceMap, diagnostics); }

    private void reset() {
        macros.clear(); dependencies.clear(); sourceMap.clear(); diagnostics.clear(); included.clear(); stack.clear();
        expandedBytes = 0; includeCount = 0; macroExpansions = 0;
    }

    private void error(String path, int line, String message) {
        diagnostics.add(new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR, message, path, line, 0));
    }

    private static String resolveInclude(String parent, String child) {
        if (child == null || child.isBlank() || child.indexOf('\0') >= 0) return null;
        // OptiFine/Iris shaderpack includes beginning with one slash are rooted at the pack's
        // normalized shader source view, not at the host filesystem. Strip only that anchor; the
        // ordinary provider normalization still rejects traversal and absolute host paths.
        String candidate = child.startsWith("/")
                ? child.substring(1)
                : relative(parent, child);
        return normalize(candidate);
    }

    private static String relative(String parent, String child) {
        int slash = parent.lastIndexOf('/');
        return slash < 0 ? child : parent.substring(0, slash + 1) + child;
    }

    private static String normalize(String path) {
        return ShaderPackSourceProvider.normalize(path);
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static int matchingParen(String value, int open) {
        int depth = 0;
        for (int i = open; i < value.length(); i++) {
            if (value.charAt(i) == '(') depth++;
            else if (value.charAt(i) == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static List<String> splitArguments(String value) {
        List<String> result = new ArrayList<>(); int start = 0; int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(') depth++; else if (c == ')') depth--; else if (c == ',' && depth == 0) {
                result.add(value.substring(start, i).trim()); start = i + 1;
            }
        }
        if (!value.isBlank() || !result.isEmpty()) result.add(value.substring(start).trim());
        return result;
    }

    private record Macro(String name, List<String> parameters, String body) { }

    private static final class Conditional {
        private final ArrayDeque<Branch> branches = new ArrayDeque<>();
        private boolean invalid;
        void begin(boolean condition, boolean parentActive) { branches.push(new Branch(parentActive, parentActive && condition, parentActive && condition, false)); }
        void elif(boolean condition) {
            if (branches.isEmpty()) { invalid = true; return; }
            Branch b = branches.pop();
            if (b.elseSeen()) { invalid = true; branches.push(b); return; }
            boolean active = b.parent() && !b.taken() && condition;
            branches.push(new Branch(b.parent(), b.taken() || active, active, false));
        }
        void otherwise() {
            if (branches.isEmpty()) { invalid = true; return; }
            Branch b = branches.pop();
            if (b.elseSeen()) { invalid = true; branches.push(b); return; }
            boolean active = b.parent() && !b.taken();
            branches.push(new Branch(b.parent(), true, active, true));
        }
        void end() { if (branches.isEmpty()) invalid = true; else branches.pop(); }
        boolean active() { return branches.stream().allMatch(Branch::active); }
        boolean empty() { return branches.isEmpty(); }
        boolean invalid() { return invalid; }
        private record Branch(boolean parent, boolean taken, boolean active, boolean elseSeen) { }
    }
}
