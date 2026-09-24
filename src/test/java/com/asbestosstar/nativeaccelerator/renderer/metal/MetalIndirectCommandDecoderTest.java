package com.asbestosstar.nativeaccelerator.renderer.metal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class MetalIndirectCommandDecoderTest {
    private MetalIndirectCommandDecoderTest() {}

    public static void main(String[] args) {
        ByteBuffer indexed = ByteBuffer.allocateDirect(40).order(ByteOrder.nativeOrder());
        indexed.putInt(0, 36).putInt(4, 2).putInt(8, 7).putInt(12, -3).putInt(16, 11);
        indexed.putInt(20, 72).putInt(24, 1).putInt(28, 19).putInt(32, 5).putInt(36, 22);
        var a = MetalIndirectCommandDecoder.indexed(indexed, 0);
        var b = MetalIndirectCommandDecoder.indexed(indexed, 1);
        expect(36, a.indexCount(), "indexed[0].count");
        expect(-3, a.vertexOffset(), "indexed[0].baseVertex");
        expect(22, b.firstInstance(), "indexed[1].firstInstance");

        ByteBuffer draw = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        draw.putInt(0, 12).putInt(4, 3).putInt(8, 4).putInt(12, 9);
        var d = MetalIndirectCommandDecoder.draw(draw, 0);
        expect(12, d.vertexCount(), "draw.count");
        expect(9, d.firstInstance(), "draw.firstInstance");
        System.out.println("MetalIndirectCommandDecoderTest: PASS");
    }

    private static void expect(int expected, int actual, String what) {
        if (expected != actual) throw new AssertionError(what + ": expected " + expected + ", got " + actual);
    }
}

