package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded structural SPIR-V binary validator.
 *
 * <p>This class validates the five-word SPIR-V header, detects endianness from the magic number,
 * performs a shallow instruction-word-count walk to confirm the binary is internally consistent,
 * and extracts the first {@code OpEntryPoint} instruction to fill in stage and entry-point name.
 * It does not parse opcode semantics, type hierarchies, or IDs beyond {@code OpEntryPoint}.
 * Any failure is reported as an ERROR-severity {@link ShaderDiagnostic} rather than an exception.
 *
 * <p>The module word limit defaults to {@value #DEFAULT_MAX_WORDS} 32-bit words (~8 MiB).
 * Override with {@code nativeaccelerator.spirv.maxWords}.
 *
 * <p>SPIR-V versions 1.0 through 1.6 are fully accepted. A version newer than 1.6 emits an INFO
 * diagnostic so code compiled to a future revision can still be probed structurally.
 */
public final class SpirVValidator {

    /** SPIR-V magic number (big-endian canonical form per the specification). */
    public static final int SPIRV_MAGIC = 0x07230203;

    /** Minimum valid module: five-word header. */
    public static final int HEADER_WORDS = 5;

    /** Default maximum module size in 32-bit words (~8 MiB). */
    public static final int DEFAULT_MAX_WORDS = 2 << 20;

    /** SPIR-V opcode for OpEntryPoint. */
    private static final int OP_ENTRY_POINT = 15;

    private SpirVValidator() {
    }

    /**
     * Validates the SPIR-V binary held in {@code buffer}.
     *
     * <p>The buffer's position and limit are not modified; validation uses a private duplicate.
     *
     * @param buffer  a ByteBuffer whose remaining bytes form the complete SPIR-V module
     * @param context a human-readable name used in diagnostic messages
     * @return an immutable {@link Result}
     */
    public static Result validate(ByteBuffer buffer, String context) {
        List<ShaderDiagnostic> diagnostics = new ArrayList<>();
        String name = context == null || context.isBlank() ? "<spirv>" : context;

        if (buffer == null || !buffer.hasRemaining()) {
            return error(diagnostics, name, "SPIR-V buffer is null or empty");
        }

        ByteBuffer view = buffer.duplicate();
        int byteCount = view.remaining();

        if (byteCount < HEADER_WORDS * Integer.BYTES) {
            return error(diagnostics, name,
                    "SPIR-V binary too short: " + byteCount + " bytes (minimum " + HEADER_WORDS * 4 + ")");
        }
        if (byteCount % Integer.BYTES != 0) {
            return error(diagnostics, name,
                    "SPIR-V binary size " + byteCount + " is not a multiple of 4");
        }

        int wordCount = byteCount / Integer.BYTES;
        int maxWords = Integer.getInteger("nativeaccelerator.spirv.maxWords", DEFAULT_MAX_WORDS);
        if (maxWords <= 0) maxWords = DEFAULT_MAX_WORDS;
        if (wordCount > maxWords) {
            return error(diagnostics, name,
                    "SPIR-V module exceeds word limit: " + wordCount + " > " + maxWords);
        }

        // Detect module byte order from the magic number.
        view.order(ByteOrder.nativeOrder());
        int rawMagic = view.getInt(view.position());

        boolean littleEndian;
        if (rawMagic == SPIRV_MAGIC) {
            littleEndian = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN);
        } else if (rawMagic == Integer.reverseBytes(SPIRV_MAGIC)) {
            littleEndian = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN);
        } else {
            return error(diagnostics, name,
                    "not a SPIR-V binary: magic 0x" + Integer.toHexString(rawMagic).toUpperCase()
                            + " (expected 0x07230203)");
        }

        ByteOrder moduleOrder = littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        view.order(moduleOrder);
        int base = view.position();

        int magic       = view.getInt(base);
        int versionWord = view.getInt(base + 4);
        int generator   = view.getInt(base + 8);
        int bound       = view.getInt(base + 12);
        int schema      = view.getInt(base + 16);

        if (magic != SPIRV_MAGIC) {
            return error(diagnostics, name,
                    "SPIR-V magic mismatch after endian resolution: 0x" + Integer.toHexString(magic));
        }

        int major = (versionWord >>> 16) & 0xFFFF;
        int minor = (versionWord >>> 8)  & 0xFF;
        if (major == 0 && minor == 0) {
            return error(diagnostics, name, "SPIR-V version word is zero");
        }
        if (major != 1) {
            if (major == 2) {
                diagnostics.add(new ShaderDiagnostic(ShaderDiagnostic.Severity.WARNING,
                        "unexpected SPIR-V major version " + major
                                + " (only version 1 is known to this validator)",
                        name, 0, 0));
            } else {
                return error(diagnostics, name,
                        "unsupported SPIR-V major version " + major + " (only version 1 is supported)");
            }
        } else if (minor > 6) {
            diagnostics.add(new ShaderDiagnostic(ShaderDiagnostic.Severity.INFO,
                    "SPIR-V version 1." + minor + " is newer than 1.6 -- structural validation only",
                    name, 0, 0));
        }

        if (bound == 0) {
            return error(diagnostics, name, "SPIR-V bound must be greater than zero");
        }
        if (Integer.compareUnsigned(bound, 1 << 20) > 0) {
            diagnostics.add(new ShaderDiagnostic(ShaderDiagnostic.Severity.WARNING,
                    "SPIR-V bound " + Integer.toUnsignedString(bound) + " is unusually large",
                    name, 0, 0));
        }

        if (schema != 0) {
            return error(diagnostics, name,
                    "SPIR-V reserved schema word is non-zero: " + Integer.toUnsignedString(schema));
        }

        int instrBase = base + HEADER_WORDS * 4;
        int available = wordCount - HEADER_WORDS;
        WalkResult walk = walkInstructions(view, instrBase, available, diagnostics, name);
        ShaderReflection reflection = buildReflection(walk);

        if (diagnostics.stream().anyMatch(d -> d.severity() == ShaderDiagnostic.Severity.ERROR)) {
            return new Result(false, versionWord, generator, bound,
                    walk.count, wordCount, littleEndian, null, null,
                    reflection, diagnostics);
        }

        return new Result(true, versionWord, generator, bound,
                walk.count, wordCount, littleEndian,
                walk.detectedStage, walk.detectedEntryPoint,
                reflection, diagnostics);
    }

    /**
     * Validates the SPIR-V binary in the given byte array.
     *
     * @param bytes   complete SPIR-V module bytes
     * @param context human-readable name for diagnostics
     * @return an immutable {@link Result}
     */
    public static Result validate(byte[] bytes, String context) {
        if (bytes == null) {
            List<ShaderDiagnostic> d = new ArrayList<>();
            return error(d, context == null ? "<spirv>" : context, "SPIR-V byte array is null");
        }
        return validate(ByteBuffer.wrap(bytes), context);
    }

    // -----------------------------------------------------------------------
    // Result type
    // -----------------------------------------------------------------------

    /**
     * Immutable result of a bounded structural SPIR-V validation.
     *
     * <p>{@code valid} is true only when there are no ERROR-severity diagnostics.
     * {@code detectedStage} and {@code detectedEntryPoint} are non-null when an
     * {@code OpEntryPoint} instruction was found; callers with spec-level metadata
     * should prefer their own values over the detected ones.
     */
    public record Result(
            boolean valid,
            int versionWord,
            int generatorMagic,
            int bound,
            int instructionCount,
            int wordCount,
            boolean littleEndian,
            String detectedStage,
            String detectedEntryPoint,
            ShaderReflection reflection,
            List<ShaderDiagnostic> diagnostics) {

        public Result {
            reflection = reflection == null ? emptyReflection() : reflection;
            diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
        }

        /** SPIR-V major version, from bits 31..16 of the version word. */
        public int majorVersion() { return (versionWord >>> 16) & 0xFFFF; }

        /** SPIR-V minor version, from bits 15..8 of the version word. */
        public int minorVersion() { return (versionWord >>> 8) & 0xFF; }
    }

    // -----------------------------------------------------------------------
    // Instruction walk
    // -----------------------------------------------------------------------

    private record WalkResult(int count, String detectedStage, String detectedEntryPoint,
            List<ShaderReflection.InterfaceVariable> inputs,
            List<ShaderReflection.InterfaceVariable> outputs) {}

    private static WalkResult walkInstructions(ByteBuffer view, int startByte,
            int availableWords, List<ShaderDiagnostic> diagnostics, String name) {
        int offset = 0;
        int count  = 0;
        String detectedStage      = null;
        String detectedEntryPoint = null;
        Map<Integer, Integer> locations = new HashMap<>();
        Map<Integer, Integer> storageClasses = new HashMap<>();
        Map<Integer, String> names = new HashMap<>();
        List<ShaderReflection.InterfaceVariable> inputs = new ArrayList<>();
        List<ShaderReflection.InterfaceVariable> outputs = new ArrayList<>();

        while (offset < availableWords) {
            int firstWord  = view.getInt(startByte + offset * 4);
            int instrWords = (firstWord >>> 16) & 0xFFFF;
            int opcode     = firstWord & 0xFFFF;

            if (instrWords == 0) {
                diagnostics.add(new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR,
                        "SPIR-V instruction at word offset " + (HEADER_WORDS + offset)
                                + " has zero word count (word count 0)",
                        name, 0, 0));
                return new WalkResult(count, detectedStage, detectedEntryPoint, inputs, outputs);
            }
            if (offset + instrWords > availableWords) {
                diagnostics.add(new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR,
                        "SPIR-V instruction at word offset " + (HEADER_WORDS + offset)
                                + " extends past end of module (count=" + instrWords
                                + ", remaining=" + (availableWords - offset) + ")",
                        name, 0, 0));
                return new WalkResult(count, detectedStage, detectedEntryPoint, inputs, outputs);
            }

            // OpEntryPoint: word0=(wc<<16|15), word1=ExecutionModel, word2=id, word3..=name
            if (opcode == OP_ENTRY_POINT && detectedStage == null && instrWords >= 4) {
                int execModel = view.getInt(startByte + (offset + 1) * 4);
                detectedStage = executionModelToStage(execModel);
                detectedEntryPoint = readSpvString(view, startByte + (offset + 3) * 4,
                        instrWords - 3);
            } else if (opcode == 71 && instrWords >= 4) { // OpDecorate
                int target = view.getInt(startByte + (offset + 1) * 4);
                int decoration = view.getInt(startByte + (offset + 2) * 4);
                if (decoration == 30) {
                    locations.put(target, view.getInt(startByte + (offset + 3) * 4));
                }
            } else if (opcode == 5 && instrWords >= 3) { // OpName
                int target = view.getInt(startByte + (offset + 1) * 4);
                names.put(target, readSpvString(view, startByte + (offset + 2) * 4,
                        instrWords - 2));
            } else if (opcode == 59 && instrWords >= 4) { // OpVariable
                int target = view.getInt(startByte + (offset + 2) * 4);
                int storage = view.getInt(startByte + (offset + 3) * 4);
                storageClasses.put(target, storage);
            }

            if (opcode == 59 && instrWords >= 4) {
                int target = view.getInt(startByte + (offset + 2) * 4);
                Integer location = locations.get(target);
                if (location != null) {
                    String variableName = names.getOrDefault(target, "id" + target);
                    int storage = view.getInt(startByte + (offset + 3) * 4);
                    ShaderReflection.InterfaceVariable variable =
                            new ShaderReflection.InterfaceVariable(variableName, location, "unknown");
                    if (storage == 1) inputs.add(variable);
                    if (storage == 3) outputs.add(variable);
                }
            }

            offset += instrWords;
            count++;
        }
        return new WalkResult(count, detectedStage, detectedEntryPoint, inputs, outputs);
    }

    private static ShaderReflection buildReflection(WalkResult walk) {
        return new ShaderReflection(walk.inputs, walk.outputs, List.of(), 0);
    }

    /** Reads a null-terminated UTF-8 string packed into SPIR-V 32-bit words. */
    private static String readSpvString(ByteBuffer view, int startByte, int maxWords) {
        StringBuilder sb = new StringBuilder(32);
        outer:
        for (int w = 0; w < maxWords; w++) {
            int word = view.getInt(startByte + w * 4);
            for (int shift = 0; shift < 32; shift += 8) {
                byte b = (byte) ((word >>> shift) & 0xFF);
                if (b == 0) break outer;
                sb.append((char) (b & 0xFF));
            }
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Maps a SPIR-V ExecutionModel operand to a lower-case stage name, or null if unknown. */
    private static String executionModelToStage(int model) {
        return switch (model) {
            case 0 -> "vertex";
            case 1 -> "tesscontrol";
            case 2 -> "tesseval";
            case 3 -> "geometry";
            case 4 -> "fragment";
            case 5 -> "compute";
            default -> null;
        };
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Result error(List<ShaderDiagnostic> diagnostics, String name, String message) {
        diagnostics.add(new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR, message, name, 0, 0));
        return new Result(false, 0, 0, 0, 0, 0, true, null, null,
                emptyReflection(), diagnostics);
    }

    private static ShaderReflection emptyReflection() {
        return new ShaderReflection(List.of(), List.of(), List.of(), 0);
    }
}
