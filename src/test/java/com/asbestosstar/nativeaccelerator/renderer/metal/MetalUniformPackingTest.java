package com.asbestosstar.nativeaccelerator.renderer.metal;

public final class MetalUniformPackingTest {
    public static void main(String[] args) {
        testOverflowPacking();
        testOptimizedResourceCompaction();
        testSamplerCompaction();
        System.out.println("Metal MSL layout tests: PASS");
    }

    private static void testOverflowPacking() {
        String source = """
                #include <metal_stdlib>
                using namespace metal;
                struct U0 { float4 a; }; struct U1 { float4 a; }; struct U2 { float4 a; };
                struct U3 { float4 a; }; struct U4 { float4 a; };
                vertex float4 main0(
                    constant U0& u0 [[buffer(0)]],
                    constant U1& u1 [[buffer(1)]],
                    constant U2& u2 [[buffer(2)]],
                    constant U3& u3 [[buffer(3)]],
                    constant U4& u4 [[buffer(4)]],
                    uint vid [[vertex_id]])
                { return u0.a + u1.a + u2.a + u3.a + u4.a + float4(vid); }
                """;
        MetalUniformPacking.Result r = MetalUniformPacking.lower(source, "main0", 5);
        require(r.physicalUniformCount() == 4, "overflow physical count");
        require(r.physicalSlot(0) == 0 && r.physicalSlot(1) == 1 && r.physicalSlot(2) == 2, "direct slots");
        require(r.packedOffset(3) == 0 && r.packedOffset(4) == 512, "packed offsets");
        require(r.packedBytes() == 1024, "packed bytes");
        require(r.source().contains("_naPackedUniforms [[buffer(3)]]"), "packed slot");
        require(!r.source().contains("u4 [[buffer(4)]]"), "removed fifth physical UBO");
    }

    private static void testOptimizedResourceCompaction() {
        // Logical slot 1 and logical slot 4 were optimized away. SDL requires the surviving
        // [[buffer]] table to be compact rather than leaving the original 0,2,3 indices.
        String source = """
                #include <metal_stdlib>
                using namespace metal;
                struct U0 { float4 a; }; struct U2 { float4 a; }; struct U3 { float4 a; };
                vertex float4 main0(
                    constant U0& u0 [[buffer(0)]],
                    constant U2& u2 [[buffer(2)]],
                    constant U3& u3 [[buffer(3)]],
                    uint vid [[vertex_id]])
                { return u0.a + u2.a + u3.a + float4(vid); }
                """;
        MetalUniformPacking.Result r = MetalUniformPacking.lower(source, "main0", 5);
        require(r.physicalUniformCount() == 3, "active uniform count");
        require(r.physicalSlot(0) == 0, "slot0");
        require(r.physicalSlot(1) == -1, "optimized slot1");
        require(r.physicalSlot(2) == 1, "logical2 compacted to physical1");
        require(r.physicalSlot(3) == 2, "logical3 compacted to physical2");
        require(r.physicalSlot(4) == -1, "optimized push slot4");
        require(r.source().contains("u2 [[buffer(1)]]"), "MSL buffer2 compacted");
        require(r.source().contains("u3 [[buffer(2)]]"), "MSL buffer3 compacted");
        require(!r.source().contains("[[buffer(3)]]"), "no gap at buffer3");
        require(r.packedBytes() == 0, "no packing when three active");
    }

    private static void testSamplerCompaction() {
        String source = """
                #include <metal_stdlib>
                using namespace metal;
                fragment float4 main0(
                    texture2d<float> tex0 [[texture(0)]], sampler samp0 [[sampler(0)]],
                    texture2d<float> tex2 [[texture(2)]], sampler samp2 [[sampler(2)]])
                { return tex0.sample(samp0, float2(0)) + tex2.sample(samp2, float2(0)); }
                """;
        MetalSamplerLayout.Result r = MetalSamplerLayout.lower(source, "main0", 3);
        require(r.samplerCount() == 2, "sampler active count");
        require(r.physicalSlot(0) == 0, "sampler0");
        require(r.physicalSlot(1) == -1, "optimized sampler1");
        require(r.physicalSlot(2) == 1, "sampler2 compacted");
        require(r.source().contains("tex2 [[texture(1)]]"), "texture compacted");
        require(r.source().contains("samp2 [[sampler(1)]]"), "sampler compacted");
    }

    private static void require(boolean value, String what) {
        if (!value) throw new AssertionError(what);
    }
}

