package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslProfile;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderDiagnostic;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderModule;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderSourceSpec;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.SpirVFrontend;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.SpirVValidator;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Focused tests for {@link SpirVValidator} and {@link SpirVFrontend}.
 *
 * All tests run without any native library. The minimal SPIR-V modules are synthesized
 * in-memory; they are structurally valid enough to pass the header and instruction-stream
 * checks but do not represent semantically correct Vulkan shaders.
 */
public final class SpirVValidatorTest {

    public static void main(String[] args) {
        // Validator tests.
        testAcceptsMinimalLittleEndianModule();
        testAcceptsMinimalBigEndianModule();
        testRejectsModuleTooShort();
        testRejectsNonMultipleOf4Bytes();
        testRejectsWrongMagic();
        testRejectsZeroWordCountInstruction();
        testRejectsInstructionPastEnd();
        testRejectsVersionTooOld();
        testRejectsVersionTooNew();
        testRejectsNegativeIdBound();
        testRejectsNonZeroSchema();
        testExtractsStageFromEntryPoint();
        testExtractsInputOutputLocations();
        testNullBufferRejectsGracefully();
        testBufferPositionIsPreserved();

        // Frontend tests.
        testFrontendHexRoundTrip();
        testFrontendDirectBinary();
        testFrontendRejectsOddHex();
        testFrontendRejectsInvalidHexChars();
        testFrontendResolvesStageFromBinary();
        testFrontendNullBufferReturnsInvalidModule();

        System.out.println("SpirVValidatorTest: PASS");
    }

    // -------------------------------------------------------------------------
    // SpirVValidator tests
    // -------------------------------------------------------------------------

    private static void testAcceptsMinimalLittleEndianModule() {
        ByteBuffer buf = minimalModule(ByteOrder.LITTLE_ENDIAN, "main", 0 /* vertex */);
        SpirVValidator.Result r = SpirVValidator.validate(buf, "test.spv");
        require(r.valid(), "minimal LE module should be valid: " + r.diagnostics());
        require(r.diagnostics().stream().noneMatch(d -> d.severity() == ShaderDiagnostic.Severity.ERROR),
                "no errors expected");
    }

    private static void testAcceptsMinimalBigEndianModule() {
        ByteBuffer buf = minimalModule(ByteOrder.BIG_ENDIAN, "main", 4 /* fragment */);
        SpirVValidator.Result r = SpirVValidator.validate(buf, "test.spv");
        require(r.valid(), "minimal BE module should be valid: " + r.diagnostics());
    }

    private static void testRejectsModuleTooShort() {
        ByteBuffer buf = ByteBuffer.allocateDirect(8);
        buf.putInt(SpirVValidator.SPIRV_MAGIC);
        buf.putInt(0x00010000);
        buf.flip();
        SpirVValidator.Result r = SpirVValidator.validate(buf, "short.spv");
        require(!r.valid(), "too-short module must be invalid");
        requireError(r, "short");
    }

    private static void testRejectsNonMultipleOf4Bytes() {
        ByteBuffer base = minimalModule(ByteOrder.LITTLE_ENDIAN, "main", 0);
        ByteBuffer buf = ByteBuffer.allocateDirect(base.remaining() + 1);
        buf.put(base);
        buf.put((byte) 0);
        buf.flip();
        SpirVValidator.Result r = SpirVValidator.validate(buf, "unaligned.spv");
        require(!r.valid(), "non-multiple-of-4 byte count must be invalid");
        requireError(r, "multiple of 4");
    }

    private static void testRejectsWrongMagic() {
        ByteBuffer base = minimalModule(ByteOrder.LITTLE_ENDIAN, "main", 0);
        int[] words = toWords(base, ByteOrder.LITTLE_ENDIAN);
        words[0] = 0xDEADBEEF;
        ByteBuffer buf = fromWords(words, ByteOrder.LITTLE_ENDIAN);
        SpirVValidator.Result r = SpirVValidator.validate(buf, "badmagic.spv");
        require(!r.valid(), "wrong magic must be invalid");
        requireError(r, "magic");
    }

    private static void testRejectsZeroWordCountInstruction() {
        // Build a valid header then append an instruction with word count 0.
        ByteBuffer header = headerWords(ByteOrder.LITTLE_ENDIAN, 6 /* id bound */);
        int[] words = toWords(header, ByteOrder.LITTLE_ENDIAN);
        // Overwrite the first instruction word to have wordCount=0.
        words[5] = 0x00000000; // opcode 0, wordCount 0 — invalid
        ByteBuffer buf = fromWords(words, ByteOrder.LITTLE_ENDIAN);
        SpirVValidator.Result r = SpirVValidator.validate(buf, "zerowc.spv");
        require(!r.valid(), "zero word-count instruction must be invalid");
        requireError(r, "word count 0");
    }

    private static void testRejectsInstructionPastEnd() {
        // Header plus one instruction whose word count claims more words than remain.
        int[] words = new int[] {
                SpirVValidator.SPIRV_MAGIC, 0x00010000, 0, 2, 0,
                (0xFFFF << 16) | 1
        };
        ByteBuffer buf = fromWords(words, ByteOrder.LITTLE_ENDIAN);
        SpirVValidator.Result r = SpirVValidator.validate(buf, "overflow.spv");
        require(!r.valid(), "instruction past end must be invalid");
    }

    private static void testRejectsVersionTooOld() {
        ByteBuffer base = minimalModule(ByteOrder.LITTLE_ENDIAN, "main", 0);
        int[] words = toWords(base, ByteOrder.LITTLE_ENDIAN);
        words[1] = 0x00000900; // before 1.0
        ByteBuffer buf = fromWords(words, ByteOrder.LITTLE_ENDIAN);
        SpirVValidator.Result r = SpirVValidator.validate(buf, "oldver.spv");
        require(!r.valid(), "version too old must be invalid");
        requireError(r, "version");
    }

    private static void testRejectsVersionTooNew() {
        ByteBuffer base = minimalModule(ByteOrder.LITTLE_ENDIAN, "main", 0);
        int[] words = toWords(base, ByteOrder.LITTLE_ENDIAN);
        words[1] = 0x00099999; // far future
        ByteBuffer buf = fromWords(words, ByteOrder.LITTLE_ENDIAN);
        SpirVValidator.Result r = SpirVValidator.validate(buf, "newver.spv");
        require(!r.valid(), "version too new must be invalid");
    }

    private static void testRejectsNegativeIdBound() {
        ByteBuffer base = minimalModule(ByteOrder.LITTLE_ENDIAN, "main", 0);
        int[] words = toWords(base, ByteOrder.LITTLE_ENDIAN);
        words[3] = 0; // id bound 0 is invalid
        ByteBuffer buf = fromWords(words, ByteOrder.LITTLE_ENDIAN);
        SpirVValidator.Result r = SpirVValidator.validate(buf, "zerobound.spv");
        require(!r.valid(), "id bound 0 must be invalid");
        requireError(r, "bound");
    }

    private static void testRejectsNonZeroSchema() {
        ByteBuffer base = minimalModule(ByteOrder.LITTLE_ENDIAN, "main", 0);
        int[] words = toWords(base, ByteOrder.LITTLE_ENDIAN);
        words[4] = 42; // schema must be 0
        ByteBuffer buf = fromWords(words, ByteOrder.LITTLE_ENDIAN);
        SpirVValidator.Result r = SpirVValidator.validate(buf, "schema.spv");
        require(!r.valid(), "non-zero schema must be invalid");
        requireError(r, "schema");
    }

    private static void testExtractsStageFromEntryPoint() {
        ByteBuffer buf = minimalModule(ByteOrder.LITTLE_ENDIAN, "main", 0 /* vertex */);
        SpirVValidator.Result r = SpirVValidator.validate(buf, "stage.spv");
        require(r.valid(), "should be valid: " + r.diagnostics());
        require("vertex".equals(r.detectedStage()), "stage should be vertex, got: " + r.detectedStage());
        require("main".equals(r.detectedEntryPoint()), "entry should be main, got: " + r.detectedEntryPoint());
    }

    private static void testExtractsInputOutputLocations() {
        ByteBuffer buf = moduleWithInterface(ByteOrder.LITTLE_ENDIAN);
        SpirVValidator.Result r = SpirVValidator.validate(buf, "iface.spv");
        require(r.valid(), "interface module should be valid: " + r.diagnostics());
        require(!r.reflection().inputs().isEmpty(), "should have inputs");
        require(!r.reflection().outputs().isEmpty(), "should have outputs");
        require(r.reflection().inputs().get(0).location() == 0, "input location 0");
        require(r.reflection().outputs().get(0).location() == 0, "output location 0");
    }

    private static void testNullBufferRejectsGracefully() {
        // Pass a zero-length buffer which is "null-like".
        ByteBuffer empty = ByteBuffer.allocateDirect(0);
        SpirVValidator.Result r = SpirVValidator.validate(empty, "empty.spv");
        require(!r.valid(), "empty buffer must be invalid");
        require(!r.diagnostics().isEmpty(), "should have diagnostics");
    }

    private static void testBufferPositionIsPreserved() {
        ByteBuffer buf = minimalModule(ByteOrder.LITTLE_ENDIAN, "main", 0);
        int origPos   = buf.position();
        int origLimit = buf.limit();
        SpirVValidator.validate(buf, "pos.spv");
        require(buf.position() == origPos, "position must not change");
        require(buf.limit()    == origLimit, "limit must not change");
    }

    // -------------------------------------------------------------------------
    // SpirVFrontend tests
    // -------------------------------------------------------------------------

    private static void testFrontendHexRoundTrip() {
        ByteBuffer raw = minimalModule(ByteOrder.LITTLE_ENDIAN, "main", 4 /* fragment */);
        String hex = bufToHex(raw.duplicate());
        ShaderSourceSpec spec = new ShaderSourceSpec(hex, "frag.spv.hex", "460",
                GlslProfile.VULKAN, "fragment");
        ShaderModule m = new SpirVFrontend().compile(spec);
        require(m.isValid(), "hex-encoded SPIR-V should produce a valid module: " + m.diagnostics());
        require(m.spirv() != null && m.spirv().remaining() > 0, "spirv ByteBuffer should be non-empty");
    }

    private static void testFrontendDirectBinary() {
        ByteBuffer raw = minimalModule(ByteOrder.LITTLE_ENDIAN, "main", 0 /* vertex */);
        ShaderSourceSpec spec = new ShaderSourceSpec("#version 460\nvoid main(){}", "vert.spv",
                "460", GlslProfile.VULKAN, "vertex");
        ShaderModule m = new SpirVFrontend().compile(raw, spec);
        require(m.isValid(), "direct binary should produce a valid module: " + m.diagnostics());
        require("vertex".equals(m.source().stage()), "stage should be vertex");
    }

    private static void testFrontendRejectsOddHex() {
        // Hex string with odd character count.
        ShaderSourceSpec spec = new ShaderSourceSpec("0307230", "odd.hex", "460",
                GlslProfile.VULKAN, "vertex");
        ShaderModule m = new SpirVFrontend().compile(spec);
        require(!m.isValid(), "odd hex count must produce invalid module");
        requireModuleError(m, "odd");
    }

    private static void testFrontendRejectsInvalidHexChars() {
        ShaderSourceSpec spec = new ShaderSourceSpec("GGGG0000", "badhex.hex", "460",
                GlslProfile.VULKAN, "vertex");
        ShaderModule m = new SpirVFrontend().compile(spec);
        require(!m.isValid(), "invalid hex chars must produce invalid module");
        requireModuleError(m, "hex");
    }

    private static void testFrontendResolvesStageFromBinary() {
        // Spec with stage "unknown" should be resolved from the binary's OpEntryPoint.
        ByteBuffer raw = minimalModule(ByteOrder.LITTLE_ENDIAN, "main", 4 /* fragment */);
        ShaderSourceSpec spec = new ShaderSourceSpec("#version 460\nvoid main(){}", "test.spv",
                "460", GlslProfile.VULKAN, "unknown");
        ShaderModule m = new SpirVFrontend().compile(raw, spec);
        require(m.isValid(), "should be valid: " + m.diagnostics());
        require("fragment".equals(m.source().stage()),
                "stage should be resolved to fragment, got: " + m.source().stage());
    }

    private static void testFrontendNullBufferReturnsInvalidModule() {
        ShaderSourceSpec spec = new ShaderSourceSpec("#version 460\nvoid main(){}", "null.spv",
                "460", GlslProfile.VULKAN, "vertex");
        ShaderModule m = new SpirVFrontend().compile(null, spec);
        require(!m.isValid(), "null binary must produce invalid module");
        require(!m.diagnostics().isEmpty(), "must have at least one diagnostic");
    }

    // -------------------------------------------------------------------------
    // Minimal SPIR-V module builders
    // -------------------------------------------------------------------------

    /**
     * Builds the smallest structurally valid SPIR-V module:
     * header + one OpCapability + one OpEntryPoint.
     */
    private static ByteBuffer minimalModule(ByteOrder order, String entryName, int execModel) {
        // OpCapability Shader — wordCount=2, opcode=17
        int[] capWord = { (2 << 16) | 17, 1 /* Shader */ };
        // OpEntryPoint — opcode=15; words: execModel, entryId, name-words, (no interface ids)
        int[] nameWords = encodeString(entryName);
        int[] epWords = new int[3 + nameWords.length];
        epWords[0] = ((epWords.length << 16) | 15);
        epWords[1] = execModel;
        epWords[2] = /* entry-point id */ 1; // we use id 1
        System.arraycopy(nameWords, 0, epWords, 3, nameWords.length);
        epWords[0] = ((epWords.length) << 16) | 15;

        int totalWords = 5 + capWord.length + epWords.length;
        int[] all = new int[totalWords];
        all[0] = SpirVValidator.SPIRV_MAGIC;
        all[1] = 0x00010000; // version 1.0
        all[2] = 0x00000000; // generator
        all[3] = 2;          // id bound (max id used + 1)
        all[4] = 0;          // schema
        System.arraycopy(capWord, 0, all, 5, capWord.length);
        System.arraycopy(epWords, 0, all, 5 + capWord.length, epWords.length);
        return fromWords(all, order);
    }

    /**
     * Builds a module with OpVariable declarations for input (location 0) and output (location 0),
     * plus matching OpDecorate and OpName instructions. This exercises the reflection extractor.
     */
    private static ByteBuffer moduleWithInterface(ByteOrder order) {
        // ids: 1=entryPoint, 2=inputVar, 3=outputVar, 4=inputType, 5=outputType
        // OpCapability Shader
        int[] cap = instr(17, 1 /* Shader */);
        // OpEntryPoint Vertex id=1 "main" interface=[2,3]
        int[] nameMain = encodeString("main");
        int epLen = 3 + nameMain.length + 2; // header(1)+execModel(1)+id(1)+name+interface(2)
        int[] ep = new int[epLen];
        ep[0] = (epLen << 16) | 15;
        ep[1] = 0; // vertex
        ep[2] = 1; // entry id
        System.arraycopy(nameMain, 0, ep, 3, nameMain.length);
        ep[3 + nameMain.length]     = 2; // inputVar id
        ep[3 + nameMain.length + 1] = 3; // outputVar id

        // OpName id=2 "in_pos"
        int[] nameIn  = nameInstr(2, "in_pos");
        // OpName id=3 "out_color"
        int[] nameOut = nameInstr(3, "out_color");
        // OpDecorate id=2 Location 0
        int[] decIn  = instr(71, 2, 30 /* Location */, 0);
        // OpDecorate id=3 Location 0
        int[] decOut = instr(71, 3, 30 /* Location */, 0);
        // OpTypeFloat id=4 32
        int[] typeF  = instr(22, 4, 32);
        // OpTypePointer id=5 result=4 storageClass=Input(1)
        int[] ptrIn  = instr(32, 5, 1 /* Input */, 4);
        // OpTypePointer id=6 result=4 storageClass=Output(3)
        int[] ptrOut = instr(32, 6, 3 /* Output */, 4);
        // OpVariable id=2 typeId=5 storageClass=Input
        int[] varIn  = instr(59, 5 /* ptr type */, 2 /* result */, 1 /* Input SC */);
        // OpVariable id=3 typeId=6 storageClass=Output
        int[] varOut = instr(59, 6 /* ptr type */, 3 /* result */, 3 /* Output SC */);

        int[] all = concat(
                headerWords5(2, 7), // id bound = 7
                cap, ep, nameIn, nameOut, decIn, decOut, typeF, ptrIn, ptrOut, varIn, varOut);
        return fromWords(all, order);
    }

    /** Builds only the 5-word header (magic+version+gen+bound+schema) plus one no-op instruction. */
    private static ByteBuffer headerWords(ByteOrder order, int idBound) {
        // OpNop wordCount=1 opcode=1
        int[] all = new int[] { SpirVValidator.SPIRV_MAGIC, 0x00010000, 0, idBound, 0, (1 << 16) | 1 };
        return fromWords(all, order);
    }

    // ---- small instruction helpers ------------------------------------------

    private static int[] instr(int opcode, int... operands) {
        int[] words = new int[1 + operands.length];
        words[0] = (words.length << 16) | opcode;
        System.arraycopy(operands, 0, words, 1, operands.length);
        return words;
    }

    private static int[] nameInstr(int targetId, String name) {
        int[] nameW = encodeString(name);
        int[] words = new int[2 + nameW.length];
        words[0] = (words.length << 16) | 5; // OpName
        words[1] = targetId;
        System.arraycopy(nameW, 0, words, 2, nameW.length);
        return words;
    }

    /** Encodes a null-terminated string into little-endian SPIR-V words (LE packing). */
    private static int[] encodeString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        // include null terminator, pad to multiple of 4
        int padded = ((bytes.length + 1) + 3) & ~3;
        byte[] padBytes = new byte[padded];
        System.arraycopy(bytes, 0, padBytes, 0, bytes.length);
        int[] words = new int[padded / 4];
        for (int i = 0; i < words.length; i++) {
            words[i] = (padBytes[i * 4]     & 0xff)
                     | ((padBytes[i * 4 + 1] & 0xff) << 8)
                     | ((padBytes[i * 4 + 2] & 0xff) << 16)
                     | ((padBytes[i * 4 + 3] & 0xff) << 24);
        }
        return words;
    }

    private static int[] headerWords5(int gen, int idBound) {
        return new int[] { SpirVValidator.SPIRV_MAGIC, 0x00010000, gen, idBound, 0 };
    }

    private static int[] concat(int[]... arrays) {
        int total = 0;
        for (int[] a : arrays) total += a.length;
        int[] result = new int[total];
        int pos = 0;
        for (int[] a : arrays) { System.arraycopy(a, 0, result, pos, a.length); pos += a.length; }
        return result;
    }

    // ---- ByteBuffer helpers --------------------------------------------------

    private static ByteBuffer fromWords(int[] words, ByteOrder order) {
        ByteBuffer buf = ByteBuffer.allocateDirect(words.length * 4).order(order);
        for (int w : words) buf.putInt(w);
        buf.flip();
        return buf;
    }

    private static int[] toWords(ByteBuffer buf, ByteOrder order) {
        ByteBuffer view = buf.duplicate().order(order);
        int[] words = new int[view.remaining() / 4];
        for (int i = 0; i < words.length; i++) words[i] = view.getInt();
        return words;
    }

    private static String bufToHex(ByteBuffer buf) {
        StringBuilder sb = new StringBuilder(buf.remaining() * 2);
        while (buf.hasRemaining()) sb.append(String.format("%02x", buf.get() & 0xff));
        return sb.toString();
    }

    // ---- assertion helpers --------------------------------------------------

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void requireError(SpirVValidator.Result r, String fragment) {
        boolean found = r.diagnostics().stream()
                .filter(d -> d.severity() == ShaderDiagnostic.Severity.ERROR)
                .anyMatch(d -> d.message().toLowerCase(java.util.Locale.ROOT)
                        .contains(fragment.toLowerCase(java.util.Locale.ROOT)));
        if (!found) {
            throw new AssertionError("expected error containing '" + fragment + "' in: " + r.diagnostics());
        }
    }

    private static void requireModuleError(ShaderModule m, String fragment) {
        boolean found = m.diagnostics().stream()
                .filter(d -> d.severity() == ShaderDiagnostic.Severity.ERROR)
                .anyMatch(d -> d.message().toLowerCase(java.util.Locale.ROOT)
                        .contains(fragment.toLowerCase(java.util.Locale.ROOT)));
        if (!found) {
            throw new AssertionError("expected error containing '" + fragment + "' in: " + m.diagnostics());
        }
    }
}
