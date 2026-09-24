package com.asbestosstar.nativeaccelerator.renderer.metal;

/** Dependency-free classification checks; does not require macOS or load Metal.framework. */
public final class MacMetalCapabilitiesTest {
    private MacMetalCapabilitiesTest() {}

    public static void main(String[] args) {
        expect(MacMetalCapabilities.Tier.UNAVAILABLE,
                MacMetalCapabilities.classify(false, false, false, 0, false, false), "unavailable");
        expect(MacMetalCapabilities.Tier.MAC1_COMPAT,
                MacMetalCapabilities.classify(true, true, false, 0, false, false), "Mac1 only");
        expect(MacMetalCapabilities.Tier.MAC2_OR_NEWER,
                MacMetalCapabilities.classify(true, true, true, 0, false, false), "Mac2");
        expect(MacMetalCapabilities.Tier.MAC2_OR_NEWER,
                MacMetalCapabilities.classify(true, false, false, 0, true, false), "Metal3 future-safe");
        expect(MacMetalCapabilities.Tier.APPLE_SILICON,
                MacMetalCapabilities.classify(true, true, true, 7, false, false), "M1/Apple7");
        expect(MacMetalCapabilities.Tier.APPLE_SILICON,
                MacMetalCapabilities.classify(true, true, true, 10, true, true), "new Apple GPU");
        expect(MacMetalCapabilities.Tier.UNKNOWN,
                MacMetalCapabilities.classify(true, false, false, 0, false, false), "unknown family");

        expect(true, MacMetalCapabilities.isLegacyNvidiaDeviceName("NVIDIA GeForce GT 650M"), "NVIDIA GT 650M name");
        expect(true, MacMetalCapabilities.isLegacyNvidiaDeviceName("GeForce GTX 680"), "GeForce name without vendor prefix");
        expect(true, MacMetalCapabilities.isLegacyNvidiaDeviceName("Quadro K5000"), "Quadro name");
        expect(false, MacMetalCapabilities.isLegacyNvidiaDeviceName("AMD Radeon Pro 580"), "AMD is not NVIDIA");
        expect(false, MacMetalCapabilities.isLegacyNvidiaDeviceName("Intel Iris Plus Graphics"), "Intel is not NVIDIA");

        // OCLP may advertise Mac2/Metal3/Metal4 on a patched stack. NVIDIA still gets a hard Mac1 ceiling.
        expect(MacMetalCapabilities.Tier.MAC1_COMPAT,
                MacMetalCapabilities.classify(true, true, true, 0, true, true, true, true),
                "NVIDIA OCLP spoof is clamped to Mac1");
        expect(MacMetalCapabilities.Tier.MAC1_COMPAT,
                MacMetalCapabilities.classify(true, true, true, 10, true, true, true, true),
                "NVIDIA cannot be promoted by spoofed Apple-family bits");

        expect(false, MacMetalCapabilities.safeIndirectCommandBufferSupport(
                MacMetalCapabilities.Tier.MAC1_COMPAT, true, true, 0, true, true),
                "NVIDIA never enables graphics ICB");
        expect(false, MacMetalCapabilities.safeIndirectCommandBufferSupport(
                MacMetalCapabilities.Tier.MAC2_OR_NEWER, false, true, 0, true, false),
                "Mac2 family without Mac2 feature set is not enough");
        expect(false, MacMetalCapabilities.safeIndirectCommandBufferSupport(
                MacMetalCapabilities.Tier.MAC2_OR_NEWER, false, false, 0, true, true),
                "Mac2 feature set without family evidence is not enough");
        expect(true, MacMetalCapabilities.safeIndirectCommandBufferSupport(
                MacMetalCapabilities.Tier.MAC2_OR_NEWER, false, true, 0, true, true),
                "Mac2 family plus Mac2 feature set enables ICB");
        expect(true, MacMetalCapabilities.safeIndirectCommandBufferSupport(
                MacMetalCapabilities.Tier.APPLE_SILICON, false, false, 7, true, true),
                "Apple7 plus Mac2 feature set enables ICB");

        System.out.println("MacMetalCapabilitiesTest: PASS");
    }

    private static void expect(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
        }
    }
}

