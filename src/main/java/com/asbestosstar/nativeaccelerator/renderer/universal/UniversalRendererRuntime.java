package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.NativeVulkanRenderer;
import com.asbestosstar.nativeaccelerator.renderer.RendererBackendRuntime;
import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererNativeApi;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslFrontend;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslProfile;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.TriangleMaterial;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdBackendRegistry;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdBackendSelection;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdBuffer;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdCommandList;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdDevice;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdPipeline;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdTexture;
import com.mojang.renderpearl.api.device.GpuDevice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/** Opt-in bridge point; ordinary Minecraft startup remains unchanged by default. */
public final class UniversalRendererRuntime {
    private static final UrdBackendRegistry REGISTRY = UrdBackendRegistry.global();
    private static volatile String successfulDemo;

    private UniversalRendererRuntime() {
    }

    public static void backendCreated(RendererBackendRuntime.Backend backend, GpuDevice device) {
        if (!Boolean.getBoolean("nativeaccelerator.urd.enabled")) {
            ShaderpackPostProcessRuntime.backendCreated(backend, device, null);
            return;
        }

        // Opt-in native Vulkan path: uses NativeVulkanRenderer's own session rather than
        // wrapping the RenderPearl GpuDevice. Enabled only when the property is set AND
        // the renderer is active with a confirmed API handle. Default: disabled.
        if (Boolean.getBoolean("nativeaccelerator.urd.nativeVulkan")
                && backend == RendererBackendRuntime.Backend.VULKAN) {
            RendererNativeApi nativeApi = NativeVulkanRenderer.api().orElse(null);
            if (nativeApi != null) {
                UrdBackendSelection nativeSelection = REGISTRY.registerNativeVulkan(nativeApi);
                ShaderpackPostProcessRuntime.backendCreated(backend, device, nativeSelection.device());
                if (!nativeSelection.active()) {
                    report("NATIVE_VULKAN BLOCKED: " + nativeSelection.reason()
                            + " — falling through to RenderPearl path");
                    // Fall through to the RenderPearl registration path below.
                } else {
                    if (!nativeSelection.status().equals(UrdBackendSelection.Status.DUPLICATE)) {
                        report("NATIVE_VULKAN ACTIVE: " + nativeSelection.adapter().name()
                                + " ownership=" + nativeSelection.ownership());
                    }
                    runDemoIfRequested(nativeSelection, backend);
                    return;
                }
            } else {
                report("NATIVE_VULKAN requested but NativeVulkanRenderer is not active; "
                        + "falling through to RenderPearl path");
            }
        }

        UrdBackendSelection selection = REGISTRY.register(backend, device);
        ShaderpackPostProcessRuntime.backendCreated(backend, device, selection.device());
        if (!selection.active()) {
            report("BLOCKED: " + selection.reason());
            return;
        }
        runDemoIfRequested(selection, backend);
    }

    /**
     * Runs the optional URD triangle demo for a registered selection when the
     * {@code nativeaccelerator.urd.demo} property requests it and the backend matches.
     * Shared by both the native Vulkan path and the RenderPearl path so neither
     * duplicates the demo dispatch logic.
     */
    private static void runDemoIfRequested(UrdBackendSelection selection, RendererBackendRuntime.Backend backend) {
        if (!selection.active()) return;
        String demo = System.getProperty("nativeaccelerator.urd.demo", "off").trim().toLowerCase(Locale.ROOT);
        if ("off".equals(demo) || demo.isBlank()) return;
        if (!"opengl".equals(demo) && !"vulkan".equals(demo) && !"metal".equals(demo)) {
            report("BLOCKED: unknown or ambiguous URD demo '" + demo + "' (choose opengl, vulkan, or metal)");
            return;
        }
        if (("opengl".equals(demo) && backend != RendererBackendRuntime.Backend.OPENGL)
                || ("vulkan".equals(demo) && backend != RendererBackendRuntime.Backend.VULKAN)
                || ("metal".equals(demo) && backend != RendererBackendRuntime.Backend.METAL)) {
            report("BLOCKED: requested " + demo + " demo but Minecraft created " + backend.name().toLowerCase(Locale.ROOT));
            return;
        }
        if (demo.equals(successfulDemo)) return;
        try {
            runTriangle(selection.device(), backend);
            successfulDemo = demo;
        } catch (Throwable failure) {
            successfulDemo = null;
            report("BLOCKED: triangle demo failed: " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : " - " + failure.getMessage()));
        }
    }

    private static void runTriangle(UrdDevice device, RendererBackendRuntime.Backend backend) {
        TriangleMaterial material = TriangleMaterial.load(UniversalRendererRuntime.class.getClassLoader())
                .forProfile(backend == RendererBackendRuntime.Backend.OPENGL ? GlslProfile.OPENGL : GlslProfile.VULKAN);
        var frontend = new GlslFrontend();
        var vertex = frontend.compile(material.vertex());
        var fragment = frontend.compile(material.fragment());
        if (!vertex.isValid() || !fragment.isValid()) {
            throw new IllegalStateException("shader compilation diagnostics: "
                    + vertex.diagnostics() + "; " + fragment.diagnostics());
        }
        UrdShaderValidation.validateTriangle(vertex, fragment);
        try (UrdBuffer vertices = device.createBuffer(60);
             UrdTexture target = device.createTexture(64, 64);
             UrdPipeline pipeline = device.createPipeline(vertex, fragment);
             UrdCommandList commands = device.createCommandList()) {
            ByteBuffer data = ByteBuffer.allocateDirect(60).order(ByteOrder.nativeOrder());
            data.putFloat(-0.75f).putFloat(-0.75f).putFloat(1.0f).putFloat(0.0f).putFloat(0.0f);
            data.putFloat(0.75f).putFloat(-0.75f).putFloat(0.0f).putFloat(1.0f).putFloat(0.0f);
            data.putFloat(0.0f).putFloat(0.75f).putFloat(0.0f).putFloat(0.0f).putFloat(1.0f).flip();
            vertices.upload(0, data);
            commands.beginPass("nativeaccelerator:urd-triangle", target);
            commands.setPipeline(pipeline);
            commands.setVertexBuffer(0, vertices, 0);
            commands.draw(3, 0);
            commands.endPass();
            commands.submit();
            ByteBuffer pixels = device.readback(target);
            UrdPixelValidation.validateRgba8Readback(pixels, target.width(), target.height());
            UrdPixelValidation.Result validation = UrdPixelValidation.validateTriangle(pixels, target.width(), target.height());
            report("GPU_TESTED: " + backend.name().toLowerCase(Locale.ROOT)
                    + " triangle pixel validation passed (readback " + validation.width() + "x"
                    + validation.height() + ", center rgba=" + validation.red() + "," + validation.green()
                    + "," + validation.blue() + "," + validation.alpha() + ", readback hash "
                    + validation.readbackHash().substring(0, 12) + ", shader hashes "
                    + material.vertex().sourceHash().substring(0, 12) + "/"
                    + material.fragment().sourceHash().substring(0, 12) + ")");
            System.out.println("NATIVEACCELERATOR_GPU_EVIDENCE: PASS");
        }
    }

    private static void report(String message) {
        System.out.println("[Native Accelerator] URD " + message);
    }
}
