package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic, loader-neutral description of a shader's reflected interface.
 *
 * <p>Reflection is represented as data rather than graphics-loader objects. Backends can therefore
 * compare an interface, persist it beside compiled output, and report incompatibilities without
 * linking this package to Minecraft, Vulkan, Metal, or a particular shader compiler. Collections
 * are copied and sorted by their canonical values so reflection-library enumeration order cannot
 * change an identity.</p>
 */
public final class ShaderReflectionManifest {
    private static final String FORMAT = "shader-reflection-manifest-v1";

    private final String stage;
    private final String entryPoint;
    private final List<ResourceBinding> resources;
    private final List<InterfaceVariable> inputs;
    private final List<InterfaceVariable> outputs;
    private final List<PushConstantRange> pushConstants;
    private final List<SpecializationConstant> specializationConstants;

    public ShaderReflectionManifest(String stage,
            String entryPoint,
            List<ResourceBinding> resources,
            List<InterfaceVariable> inputs,
            List<InterfaceVariable> outputs,
            List<PushConstantRange> pushConstants,
            List<SpecializationConstant> specializationConstants) {
        this.stage = required("stage", stage);
        this.entryPoint = required("entryPoint", entryPoint);
        this.resources = canonical("resources", resources, ResourceBinding::canonicalForm);
        this.inputs = canonical("inputs", inputs, InterfaceVariable::canonicalForm);
        this.outputs = canonical("outputs", outputs, InterfaceVariable::canonicalForm);
        this.pushConstants = canonical("pushConstants", pushConstants, PushConstantRange::canonicalForm);
        this.specializationConstants = canonical("specializationConstants", specializationConstants,
                SpecializationConstant::canonicalForm);
    }

    public static ShaderReflectionManifest empty(String stage, String entryPoint) {
        return new ShaderReflectionManifest(stage, entryPoint, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public String stage() { return stage; }
    public String entryPoint() { return entryPoint; }
    public List<ResourceBinding> resources() { return resources; }
    public List<InterfaceVariable> inputs() { return inputs; }
    public List<InterfaceVariable> outputs() { return outputs; }
    public List<PushConstantRange> pushConstants() { return pushConstants; }
    public List<SpecializationConstant> specializationConstants() { return specializationConstants; }

    public String canonicalForm() {
        StringBuilder out = new StringBuilder(512).append(FORMAT);
        append(out, stage);
        append(out, entryPoint);
        appendList(out, resources);
        appendList(out, inputs);
        appendList(out, outputs);
        appendList(out, pushConstants);
        appendList(out, specializationConstants);
        return out.toString();
    }

    /** SHA-256 of the canonical interface description, in lowercase hexadecimal. */
    public String digest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalForm().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void appendList(StringBuilder out, List<? extends Canonical> values) {
        out.append('|').append(values.size());
        for (Canonical value : values) append(out, value.canonicalForm());
    }

    private static void append(StringBuilder out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.append('|').append(bytes.length).append(':').append(value);
    }

    private static String required(String name, String value) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    private static <T extends Canonical> List<T> canonical(String name, List<T> values,
            java.util.function.Function<T, String> ignored) {
        Objects.requireNonNull(values, name);
        var copy = new java.util.ArrayList<T>(values.size());
        for (T value : values) copy.add(Objects.requireNonNull(value, name + " entry"));
        copy.sort(java.util.Comparator.comparing(Canonical::canonicalForm));
        return List.copyOf(copy);
    }

    private interface Canonical { String canonicalForm(); }

    public record ResourceBinding(int set, int binding, String kind, String type, int arrayLength,
            boolean readOnly) implements Canonical {
        public ResourceBinding {
            if (set < 0 || binding < 0 || arrayLength < 0) throw new IllegalArgumentException("resource indices must be non-negative");
            kind = required("kind", kind);
            type = required("type", type);
        }
        @Override public String canonicalForm() {
            return set + "\u0000" + binding + "\u0000" + kind + "\u0000" + type + "\u0000" + arrayLength + "\u0000" + readOnly;
        }
    }

    public record InterfaceVariable(int location, int component, String type, int vectorWidth,
            String interpolation) implements Canonical {
        public InterfaceVariable {
            if (location < 0 || component < 0 || vectorWidth < 1) throw new IllegalArgumentException("interface indices/width must be positive");
            type = required("type", type);
            interpolation = interpolation == null ? "" : interpolation.trim();
        }
        @Override public String canonicalForm() {
            return location + "\u0000" + component + "\u0000" + type + "\u0000" + vectorWidth + "\u0000" + interpolation;
        }
    }

    public record PushConstantRange(int offset, int size, String type) implements Canonical {
        public PushConstantRange {
            if (offset < 0 || size <= 0) throw new IllegalArgumentException("push-constant offset/size invalid");
            type = required("type", type);
        }
        @Override public String canonicalForm() { return offset + "\u0000" + size + "\u0000" + type; }
    }

    public record SpecializationConstant(int id, String type, String defaultValue) implements Canonical {
        public SpecializationConstant {
            if (id < 0) throw new IllegalArgumentException("specialization id must be non-negative");
            type = required("type", type);
            defaultValue = defaultValue == null ? "" : defaultValue.trim();
        }
        @Override public String canonicalForm() { return id + "\u0000" + type + "\u0000" + defaultValue; }
    }
}
