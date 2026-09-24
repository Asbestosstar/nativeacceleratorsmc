package com.asbestosstar.nativeaccelerator.renderer.metal;

import java.util.Arrays;

public final class MetalQuadTriangulationPureTest {
    private static int[] generate(int vertexCount) {
        if ((vertexCount & 3) != 0 || vertexCount < 0) throw new IllegalArgumentException();
        int[] out = new int[(vertexCount / 4) * 6];
        int o = 0;
        for (int base = 0; base < vertexCount; base += 4) {
            out[o++] = base; out[o++] = base + 1; out[o++] = base + 2;
            out[o++] = base + 2; out[o++] = base + 3; out[o++] = base;
        }
        return out;
    }
    public static void main(String[] args) {
        if (!Arrays.equals(generate(8), new int[]{0,1,2,2,3,0,4,5,6,6,7,4}))
            throw new AssertionError();
        System.out.println("MetalQuadTriangulationPureTest: PASS");
    }
}

