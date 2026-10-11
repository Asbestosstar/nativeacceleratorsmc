package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslPreprocessor;

import java.util.Map;
import java.util.Optional;

public final class GlslPreprocessorTest {
    public static void main(String[] args) {
        Map<String, String> files = Map.of(
                "main.glsl", "#version 460\n#include \"inc/common.glsl\"\n#if ENABLED\nVALUE\n#else\nwrong\n#endif\n",
                "inc/common.glsl", "#define VALUE 42\n");
        GlslPreprocessor.Result result = new GlslPreprocessor(path -> Optional.ofNullable(files.get(path))).process("main.glsl", Map.of("ENABLED", "1"));
        if (!result.successful() || !result.source().contains("42") || result.source().contains("wrong")) {
            throw new AssertionError("include/conditional preprocessing failed: " + result.diagnostics());
        }
        Map<String, String> cycle = Map.of("a.glsl", "#include \"b.glsl\"\n", "b.glsl", "#include \"a.glsl\"\n");
        GlslPreprocessor.Result cycleResult = new GlslPreprocessor(path -> Optional.ofNullable(cycle.get(path))).process("a.glsl");
        if (cycleResult.successful()) throw new AssertionError("include cycle accepted");
        GlslPreprocessor.Result traversal = new GlslPreprocessor(path -> Optional.ofNullable(files.get(path))).process("../main.glsl");
        if (traversal.successful()) throw new AssertionError("unsafe root accepted");
        System.out.println("GlslPreprocessorTest: PASS");
    }
}
