package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import org.joml.Vector4fc;
import org.joml.Vector4f;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLVulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * Opt-in RenderPearl draw harness on a real SDL window.
 *
 * <p>Drives the same public RenderPearl surface Minecraft uses: backend load, device, window, surface,
 * pipeline compile, vertex upload, render pass, blit, present, resize, and teardown. It never invokes
 * Minecraft's client window construction. Its pass condition is that every stage completes; it does
 * <em>not</em> read back pixels, because the surface encoder does not support offscreen readback.</p>
 *
 * <p>Run only through {@code scripts/run-native-vulkan-renderpearl-triangle.sh}.</p>
 */
public final class NativeVulkanRenderPearlTriangleHarness {
    private static final Identifier VERTEX_SHADER = Identifier.fromNamespaceAndPath("nativeaccelerator", "triangle_vertex");
    private static final Identifier FRAGMENT_SHADER = Identifier.fromNamespaceAndPath("nativeaccelerator", "triangle_fragment");
    private static final int FRAMES_PER_SIZE = 8;

    private static final String VERTEX_GLSL = """
            #version 450
            layout(location = 0) in vec2 Position;
            void main() {
                gl_Position = vec4(Position, 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT_GLSL = """
            #version 450
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = vec4(1.0, 0.5, 0.0, 1.0);
            }
            """;

    private NativeVulkanRenderPearlTriangleHarness() {
    }

    public static void main(String[] args) throws Exception {
        String stage = "SDL video";
        boolean video = false;
        boolean loader = false;
        long window = 0L;
        NativeVulkanGpuBackend backend = new NativeVulkanGpuBackend();
        GpuDevice device = null;
        GpuSurface surface = null;
        GpuTexture texture = null;
        GpuTextureView view = null;
        GpuBuffer vertices = null;
        CompiledRenderPipeline pipeline = null;
        boolean renderSystemInitialized = false;
        boolean clearOnly = "1".equals(System.getenv("NATIVEACCELERATOR_RENDERPEARL_CLEAR_ONLY"));
        Throwable failure = null;
        try {
            evidence(stage);
            if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) throw new IllegalStateException(SDLError.SDL_GetError());
            video = true;

            stage = "SDL Vulkan loader";
            evidence(stage);
            String vulkanLibrary = System.getProperty("nativeaccelerator.vulkan.sdlLibrary");
            if (!SDLVulkan.SDL_Vulkan_LoadLibrary(vulkanLibrary)) {
                throw new IllegalStateException(SDLError.SDL_GetError());
            }
            loader = true;

            stage = "backend loadLibrary";
            evidence(stage);
            backend.loadLibrary();

            stage = "device creation";
            evidence(stage);
            device = backend.createDevice(new GpuDebugOptions(0, false, false, false));
            DeviceInfo info = device.getDeviceInfo();
            evidence("device created: " + info);

            stage = "RenderSystem init";
            evidence(stage);
            RenderSystem.initRenderer(device);
            renderSystemInitialized = true;

            stage = "SDL window and WSI attach";
            evidence(stage);
            window = backend.createWindow("Native Accelerator RenderPearl triangle", 640, 480,
                    SDLVideo.SDL_WINDOW_VULKAN);

            stage = "surface configure";
            evidence(stage);
            surface = device.createSurface(window, () -> false);
            int width = 640;
            int height = 480;
            surface.configure(new GpuSurface.Configuration(width, height, GpuSurface.PresentMode.FIFO));

            stage = "render target";
            evidence(stage);
            texture = device.createTexture("triangle target",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            view = device.createTextureView(texture);

            if (!clearOnly) {
                stage = "pipeline compile";
                evidence(stage);
                RenderPipeline description = RenderPipeline.builder()
                        .withLocation("nativeaccelerator/triangle")
                        .withVertexShader(VERTEX_SHADER)
                        .withFragmentShader(FRAGMENT_SHADER)
                        .withVertexBinding(0, VertexFormat.builder(0).addAttribute("Position", GpuFormat.RG32_FLOAT).build())
                        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                        .withCull(false)
                        .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                        .build();
                try (ShaderSource shaders = new TriangleShaderSource()) {
                    pipeline = device.compilePipeline(description, shaders, Runnable::run).join().finishCompile();
                }

                stage = "vertex upload";
                evidence(stage);
                ByteBuffer data = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder())
                        .putFloat(0.0f).putFloat(-0.5f)
                        .putFloat(0.5f).putFloat(0.5f)
                        .putFloat(-0.5f).putFloat(0.5f)
                        .flip();
                vertices = device.createBuffer(() -> "triangle vertices",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST, data);
            }

            stage = "acquire/render/present (initial size)";
            evidence(stage);
            renderFrames(device, surface, view, pipeline, vertices, width, height, FRAMES_PER_SIZE);

            stage = "resize/reconfigure";
            evidence(stage);
            width = 800;
            height = 600;
            if (!SDLVideo.SDL_SetWindowSize(window, width, height)) {
                throw new IllegalStateException(SDLError.SDL_GetError());
            }
            surface.configure(new GpuSurface.Configuration(width, height, GpuSurface.PresentMode.FIFO));
            view.close();
            texture.close();
            texture = device.createTexture("triangle target resized",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            view = device.createTextureView(texture);

            stage = "acquire/render/present (resized)";
            evidence(stage);
            renderFrames(device, surface, view, pipeline, vertices, width, height, FRAMES_PER_SIZE);
        } catch (Throwable thrown) {
            failure = thrown;
            System.err.println("RENDERPEARL-TRIANGLE: FAIL at " + stage + ": " + thrown);
            thrown.printStackTrace(System.err);
        } finally {
            stage = "teardown";
            failure = closeOne(failure, pipeline);
            failure = closeOne(failure, vertices);
            failure = closeOne(failure, view);
            failure = closeOne(failure, texture);
            failure = closeOne(failure, surface);
            if (renderSystemInitialized) {
                try { RenderSystem.shutdownRenderer(); }
                catch (Throwable cleanup) { failure = attach(failure, cleanup); }
            }
            failure = closeDevice(failure, device);
            if (window != 0L) {
                try { SDLVideo.SDL_DestroyWindow(window); }
                catch (Throwable cleanup) { failure = attach(failure, cleanup); }
            }
            try { backend.unloadLibrary(); } catch (Throwable cleanup) { failure = attach(failure, cleanup); }
            if (loader) SDLVulkan.SDL_Vulkan_UnloadLibrary();
            if (video) SDLInit.SDL_Quit();
        }
        if (failure != null) {
            throw failure instanceof Exception ex ? ex : new IllegalStateException(failure);
        }
        String mode = clearOnly ? "clear-only, no shader pipeline or triangle draw" : "triangle draw";
        System.out.println("RENDERPEARL-TRIANGLE: PASS (" + mode + "; acquire/render/present/resize/teardown "
                + "completed; pixel readback not performed)");
    }

    private static void renderFrames(GpuDevice device, GpuSurface surface, GpuTextureView view,
            CompiledRenderPipeline pipeline, GpuBuffer vertices, int width, int height, int frames)
            throws Exception {
        for (int frame = 0; frame < frames; frame++) {
            surface.acquireNextTexture();
            CommandEncoder encoder = device.createCommandEncoder();
            try (RenderPass pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> "triangle pass")
                    .withColorAttachment(view, Optional.<Vector4fc>of(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f)))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, width, height))
                    .build())) {
                if (pipeline != null) {
                    pass.setPipeline(pipeline);
                    pass.setVertexBuffer(0, vertices.slice());
                    pass.draw(3, 1, 0, 0);
                }
            }
            surface.blitFromTexture(encoder, view);
            surface.present();
            evidence("frame " + frame + " presented at " + width + "x" + height);
        }
    }

    private static Throwable closeOne(Throwable failure, AutoCloseable resource) {
        if (resource == null) return failure;
        try { resource.close(); } catch (Throwable cleanup) { return attach(failure, cleanup); }
        return failure;
    }

    private static Throwable closeDevice(Throwable failure, GpuDevice device) {
        if (device == null) return failure;
        try { device.close(); } catch (Throwable cleanup) { return attach(failure, cleanup); }
        return failure;
    }

    private static Throwable attach(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private static void evidence(String message) {
        System.out.println("RENDERPEARL-TRIANGLE: " + message);
    }

    private static final class TriangleShaderSource implements ShaderSource {
        @Override public String getShader(Identifier requested, ShaderType requestedType) {
            if (requestedType == ShaderType.VERTEX && VERTEX_SHADER.equals(requested)) return VERTEX_GLSL;
            if (requestedType == ShaderType.FRAGMENT && FRAGMENT_SHADER.equals(requested)) return FRAGMENT_GLSL;
            throw new IllegalArgumentException("Unknown triangle shader: " + requested + " " + requestedType);
        }

        @Override public ShaderSource.CachedIncludeSource getInclude(Identifier requested) {
            throw new IllegalArgumentException("Triangle harness declares no includes: " + requested);
        }

        @Override public void close() {
        }
    }
}
