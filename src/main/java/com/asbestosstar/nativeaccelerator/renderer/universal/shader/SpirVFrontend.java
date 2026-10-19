package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * SPIR-V binary frontend for the URD shader layer.
 *
 * <p>Accepts pre-compiled SPIR-V modules in two ways:
 *
 * <ol>
 *   <li>{@link #compile(ShaderSourceSpec)} — the {@code source} field of the spec carries the
 *       SPIR-V words encoded as lowercase hex (two characters per byte). This keeps the interface
 *       compatible with {@link ShaderFrontend} and lets callers use the same {@link ShaderSourceSpec}
 *       identity/caching machinery. The source must contain an even number of non-whitespace hex
 *       characters and must not exceed the {@link ShaderSourceSpec#DEFAULT_MAX_BYTES} source byte
 *       limit for the hex text itself.</li>
 *   <li>{@link #compile(ByteBuffer, ShaderSourceSpec)} — the binary is already decoded; the spec
 *       provides identity metadata but its {@code source} field is ignored for decoding.</li>
 * </ol>
 *
 * <p>In both cases the raw binary is passed through {@link SpirVValidator}. If the module is
 * structurally sound the validator's detected stage and entry point are used to fill in any
 * blanks left in the spec. The returned {@link ShaderModule} carries the read-only binary and
 * the minimal reflection extracted by the validator.</p>
 *
 * <p>No native library is required. Semantic validation (capability requirements, type checking,
 * linkage) is deferred to the back-end driver; this class handles only structural well-formedness
 * and interface extraction.</p>
 */
public final class SpirVFrontend implements ShaderFrontend {

    /**
     * Compiles a hex-encoded SPIR-V module carried in the {@code source} field of {@code spec}.
     *
     * <p>The source string must be a contiguous sequence of lowercase (or uppercase) hex digits,
     * optionally separated by ASCII whitespace. Each pair of digits encodes one byte in the order
     * they appear, so the first four bytes are the magic number that determines module endianness.
     * Any non-hex, non-whitespace character is an error.</p>
     */
    @Override
    public ShaderModule compile(ShaderSourceSpec spec) {
        List<ShaderDiagnostic> diagnostics = new ArrayList<>();
        ByteBuffer binary = decodeHex(spec.source(), spec.sourceName(), diagnostics);
        if (binary == null) {
            return new ShaderModule(spec, null, emptyReflection(), diagnostics);
        }
        return validateAndWrap(binary, spec, diagnostics);
    }

    /**
     * Compiles a raw SPIR-V binary.
     *
     * <p>The buffer's position and limit are not modified. The {@code spec} provides identity
     * metadata; its {@code source} field is not interpreted as hex.</p>
     *
     * @param spirv a direct or heap buffer containing the SPIR-V words
     * @param spec  identity/metadata; must not be {@code null}
     */
    public ShaderModule compile(ByteBuffer spirv, ShaderSourceSpec spec) {
        if (spirv == null) {
            List<ShaderDiagnostic> diag = new ArrayList<>();
            diag.add(new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR,
                    "SPIR-V binary buffer must not be null", spec == null ? "<null>" : spec.sourceName(), 0, 0));
            return new ShaderModule(spec, null, emptyReflection(), diag);
        }
        return validateAndWrap(spirv.duplicate(), spec, new ArrayList<>());
    }

    // -------------------------------------------------------------------------

    private static ShaderModule validateAndWrap(ByteBuffer binary, ShaderSourceSpec spec,
            List<ShaderDiagnostic> priorDiagnostics) {
        SpirVValidator.Result result = SpirVValidator.validate(binary, spec.sourceName());
        List<ShaderDiagnostic> diagnostics = new ArrayList<>(priorDiagnostics);
        diagnostics.addAll(result.diagnostics());

        if (!result.valid()) {
            return new ShaderModule(spec, null, emptyReflection(), diagnostics);
        }

        String stage      = resolveStage(spec.stage(), result.detectedStage());
        String entryPoint = resolveEntry(spec.entryPoint(), result.detectedEntryPoint());

        // Build a spec whose stage/entryPoint reflect what the binary actually contains.
        ShaderSourceSpec resolved = stage.equals(spec.stage()) && entryPoint.equals(spec.entryPoint())
                ? spec
                : new ShaderSourceSpec(spec.source(), spec.sourceName(), spec.version(),
                        spec.profile(), stage, entryPoint, spec.defines(),
                        spec.dependencyHashes(), spec.compilerIdentity(), spec.targetEnvironment());

        // Deliver a read-only copy of the binary so callers cannot mutate the words.
        ByteBuffer ro = binary.duplicate().asReadOnlyBuffer();
        return new ShaderModule(resolved, ro, result.reflection(), diagnostics);
    }

    /** Decodes a hex string into a new direct ByteBuffer, or returns null and appends diagnostics. */
    private static ByteBuffer decodeHex(String hex, String sourceName,
            List<ShaderDiagnostic> diagnostics) {
        // Strip all ASCII whitespace.
        String compact = hex.replaceAll("[ \\t\\r\\n]+", "");
        if (compact.isEmpty()) {
            diagnostics.add(new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR,
                    "SPIR-V hex source is empty", sourceName, 0, 0));
            return null;
        }
        if (compact.length() % 2 != 0) {
            diagnostics.add(new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR,
                    "SPIR-V hex source has an odd character count: " + compact.length(), sourceName, 0, 0));
            return null;
        }
        int byteCount = compact.length() / 2;
        ByteBuffer buf = ByteBuffer.allocateDirect(byteCount);
        for (int i = 0; i < byteCount; i++) {
            char hi = compact.charAt(i * 2);
            char lo = compact.charAt(i * 2 + 1);
            int hiVal = hexDigit(hi);
            int loVal = hexDigit(lo);
            if (hiVal < 0 || loVal < 0) {
                diagnostics.add(new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR,
                        "invalid hex character at byte " + i + ": '"
                        + (hiVal < 0 ? hi : lo) + "'",
                        sourceName, 0, 0));
                return null;
            }
            buf.put((byte) ((hiVal << 4) | loVal));
        }
        buf.flip();
        return buf;
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static String resolveStage(String specStage, String detected) {
        if (specStage == null || specStage.isBlank() || "unknown".equals(specStage)) {
            return detected == null || detected.isBlank() ? "unknown" : detected;
        }
        return specStage;
    }

    private static String resolveEntry(String specEntry, String detected) {
        if (specEntry == null || specEntry.isBlank()) {
            return detected == null || detected.isBlank() ? "main" : detected;
        }
        return specEntry;
    }

    private static ShaderReflection emptyReflection() {
        return new ShaderReflection(java.util.List.of(), java.util.List.of(), java.util.List.of(), 0);
    }
}
