package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackGeneration;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackGenerationLifecycle;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackOutcome;

import java.util.concurrent.atomic.AtomicInteger;

public final class ShaderpackGenerationLifecycleTest {
    public static void main(String[] args) {
        ShaderpackGeneration generation = new ShaderpackGeneration(
                "fixture", "hash", ShaderpackOutcome.EXACT_TRANSLATION, java.util.List.of(), null);
        AtomicInteger closed = new AtomicInteger();
        ShaderpackGenerationLifecycle lifecycle = new ShaderpackGenerationLifecycle();
        ShaderpackGenerationLifecycle.Published first = lifecycle.publish(generation, closed::incrementAndGet);
        AtomicInteger rejectedClosed = new AtomicInteger();
        try {
            lifecycle.publish(new ShaderpackGeneration("bad", "hash-bad",
                    ShaderpackOutcome.UNSUPPORTED, java.util.List.of("missing semantics"), null),
                    rejectedClosed::incrementAndGet);
            throw new AssertionError("non-executable generation was published");
        } catch (IllegalArgumentException expected) {
        }
        if (lifecycle.current() != first || rejectedClosed.get() != 1) {
            throw new AssertionError("failed replacement changed current generation");
        }
        try (var lease = lifecycle.acquire()) {
            if (!lease.present() || lease.published() != first) throw new AssertionError("lease");
            lifecycle.disable();
            if (closed.get() != 0) throw new AssertionError("retired generation closed while leased");
        }
        if (closed.get() != 1) throw new AssertionError("retired generation was not closed");
        lifecycle.close();
        if (closed.get() != 1) throw new AssertionError("resources closed twice");
        System.out.println("ShaderpackGenerationLifecycleTest: PASS");
    }
}
