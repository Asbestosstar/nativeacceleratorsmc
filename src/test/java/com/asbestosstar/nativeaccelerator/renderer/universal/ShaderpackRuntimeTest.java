package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackGeneration;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackOutcome;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackRuntime;

import java.util.List;

public final class ShaderpackRuntimeTest {
    public static void main(String[] args) {
        ShaderpackRuntime runtime = new ShaderpackRuntime();
        runtime.beginPreparation("one");
        if (runtime.attemptSnapshot().state() != ShaderpackRuntime.AttemptState.PREPARING
                || !runtime.attemptSnapshot().packId().equals("one")) {
            throw new AssertionError("preparation attempt was not published");
        }
        ShaderpackGeneration first = new ShaderpackGeneration("one", "hash-one", ShaderpackOutcome.NATIVE,
                List.of(), null);
        runtime.activate(first);
        if (runtime.attemptSnapshot().state() != ShaderpackRuntime.AttemptState.ACTIVE) {
            throw new AssertionError("successful attempt was not published");
        }
        if (runtime.state() != ShaderpackRuntime.State.ACTIVE || runtime.active() != first) {
            throw new AssertionError("first generation was not published");
        }
        runtime.beginPreparation("replacement");
        if (runtime.active() != null) throw new AssertionError("preparing replacement left prior generation active");
        runtime.fail(List.of("compile failed"));
        if (runtime.active() != null || runtime.state() != ShaderpackRuntime.State.FAILED) {
            throw new AssertionError("failed replacement left a stale generation active");
        }
        if (runtime.attemptSnapshot().state() != ShaderpackRuntime.AttemptState.FAILED
                || !runtime.attemptSnapshot().packId().equals("replacement")
                || !runtime.attemptSnapshot().diagnostics().contains("compile failed")) {
            throw new AssertionError("failed replacement attempt was not reported independently");
        }
        ShaderpackGeneration second = new ShaderpackGeneration("two", "hash-two",
                ShaderpackOutcome.EXACT_TRANSLATION, List.of(), null);
        runtime.beginPreparation("two");
        runtime.activate(second);
        if (runtime.active() != second || runtime.previous() == null) {
            throw new AssertionError("successful replacement did not retain prior generation history");
        }
        runtime.disable("user disabled");
        if (runtime.active() != null || runtime.state() != ShaderpackRuntime.State.DISABLED) {
            throw new AssertionError("disable did not return to vanilla state");
        }
        runtime.close();
        boolean rejected = false;
        try { runtime.beginPreparation(); } catch (IllegalStateException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("closed runtime accepted preparation");
        System.out.println("ShaderpackRuntimeTest: PASS");
    }
}
