package com.asbestosstar.nativeaccelerator.renderer.metal;

import java.util.List;

/** Dependency-light exact-order checks for the renderer candidate policy. */
public final class GraphicsBackendOrderPolicyTest {
    private static final GraphicsBackendOrderPolicy.BackendKind METAL = GraphicsBackendOrderPolicy.BackendKind.METAL;
    private static final GraphicsBackendOrderPolicy.BackendKind OPENGL = GraphicsBackendOrderPolicy.BackendKind.OPENGL;
    private static final GraphicsBackendOrderPolicy.BackendKind VULKAN = GraphicsBackendOrderPolicy.BackendKind.VULKAN;
    private static final GraphicsBackendOrderPolicy.BackendKind NATIVE = GraphicsBackendOrderPolicy.BackendKind.NATIVE_VULKAN;

    public static void main(String[] args) {
        requireOrder(GraphicsBackendOrderPolicy.Selection.OPENGL, List.of(OPENGL), false, false, false, OPENGL);
        requireOrder(GraphicsBackendOrderPolicy.Selection.METAL, List.of(METAL, OPENGL), true, false, false, METAL, OPENGL);
        requireOrder(GraphicsBackendOrderPolicy.Selection.VULKAN, List.of(VULKAN, OPENGL), false, false, false, VULKAN, OPENGL);
        requireOrder(GraphicsBackendOrderPolicy.Selection.VULKAN, List.of(VULKAN, OPENGL), false, true, false,
                VULKAN, NATIVE, OPENGL);
        requireOrder(GraphicsBackendOrderPolicy.Selection.NATIVE_VULKAN, List.of(VULKAN, OPENGL), false, true, false,
                NATIVE, VULKAN, OPENGL);
        requireOrder(GraphicsBackendOrderPolicy.Selection.NATIVE_VULKAN, List.of(VULKAN, OPENGL), false, false, false,
                VULKAN, OPENGL);
        requireOrder(GraphicsBackendOrderPolicy.Selection.DEFAULT, List.of(METAL, OPENGL, VULKAN), true, true, false,
                METAL, OPENGL, VULKAN, NATIVE);
        requireOrder(GraphicsBackendOrderPolicy.Selection.DEFAULT, List.of(OPENGL, VULKAN), false, true, false,
                OPENGL, VULKAN, NATIVE);
        requireOrder(GraphicsBackendOrderPolicy.Selection.DEFAULT, List.of(METAL, OPENGL, VULKAN), true, true, true,
                NATIVE, VULKAN, OPENGL);
        requireOrder(GraphicsBackendOrderPolicy.Selection.DEFAULT, List.of(METAL, OPENGL, VULKAN), true, false, true,
                METAL, OPENGL, VULKAN);
        System.out.println("GraphicsBackendOrderPolicyTest: PASS");
    }

    private static void requireOrder(GraphicsBackendOrderPolicy.Selection choice,
                                     List<GraphicsBackendOrderPolicy.BackendKind> vanilla,
                                     boolean metalEligible,
                                     boolean nativeEligible,
                                     boolean nativeFirst,
                                     GraphicsBackendOrderPolicy.BackendKind... expected) {
        List<GraphicsBackendOrderPolicy.BackendKind> actual =
                GraphicsBackendOrderPolicy.orderedCandidates(
                        choice, vanilla, metalEligible, nativeEligible, nativeFirst);
        List<GraphicsBackendOrderPolicy.BackendKind> wanted = List.of(expected);
        require(actual.equals(wanted), choice + " expected " + wanted + " but got " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
