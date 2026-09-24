package com.asbestosstar.nativeaccelerator.renderer.metal;

import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Small built-in fallback catalogue for Native Accelerator's graphics screen.
 *
 * Minecraft resource translations remain packaged normally, but these literal fallbacks keep this
 * diagnostic/settings screen readable even on loader combinations that fail to expose a mod lang
 * namespace during the first client resource reload.
 */
public final class GraphicsMenuText {
    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("nativeaccelerator.options.renderer", "Graphics backend"),
            Map.entry("nativeaccelerator.options.renderer.tooltip", "Select the graphics backend to use after restart."),
            Map.entry("nativeaccelerator.graphicsApi.default", "Automatic"),
            Map.entry("nativeaccelerator.graphicsApi.opengl", "SGI OpenGL"),
            Map.entry("nativeaccelerator.graphicsApi.vulkan", "Vulkan"),
            Map.entry("nativeaccelerator.graphicsApi.metal", "Metal"),
            Map.entry("nativeaccelerator.options.graphics.header", "Native Accelerator"),
            Map.entry("nativeaccelerator.options.graphics.pipeline", "Renderer / atlas"),
            Map.entry("nativeaccelerator.options.restart.tooltip", "Saved to minecraft/etc/nativeaccelerator.properties. Most engine changes apply after restart."),
            Map.entry("nativeaccelerator.options.auto", "Auto"),
            Map.entry("nativeaccelerator.options.unixTheme", "CDE / Motif theme"),
            Map.entry("nativeaccelerator.options.crtScanlines", "CRT scanlines"),
            Map.entry("nativeaccelerator.options.showUma", "Show Uma"),
            Map.entry("nativeaccelerator.options.umaOpacity", "Uma opacity"),
            Map.entry("nativeaccelerator.options.nativeVulkan", "Native Vulkan"),
            Map.entry("nativeaccelerator.options.rendererWorkers", "Renderer workers"),
            Map.entry("nativeaccelerator.options.fastStitcher", "Fast atlas stitcher"),
            Map.entry("nativeaccelerator.options.safeFastStitcher", "Safe stitcher"),
            Map.entry("nativeaccelerator.options.verifyFastStitcher", "Verify stitcher"),
            Map.entry("nativeaccelerator.options.parallelMipmaps", "Parallel mipmaps"),
            Map.entry("nativeaccelerator.options.atlasWorkers", "Atlas workers"),
            Map.entry("nativeaccelerator.options.decodedTextureCache", "Texture cache"),
            Map.entry("nativeaccelerator.options.atlasLayoutCache", "Atlas layout cache"),
            Map.entry("nativeaccelerator.options.requireVerifiedAtlas", "Verified atlas only"),
            Map.entry("nativeaccelerator.options.guiNineSliceFix", "GUI nine-slice fix"),
            Map.entry("nativeaccelerator.options.metalFrames", "Metal frames in flight"),
            Map.entry("nativeaccelerator.options.metalCpuIndirect", "Metal CPU indirect"),
            Map.entry("nativeaccelerator.options.metalYFlip", "Metal generated Y-flip"),
            Map.entry("nativeaccelerator.options.mac1Frames", "Mac1 frames in flight"),
            Map.entry("nativeaccelerator.options.mac1CycleClearedTargets", "Mac1 safe target cycling"),
            Map.entry("nativeaccelerator.options.guiRegionalClearUpload", "GUI regional clear upload"),
            Map.entry("nativeaccelerator.options.resetSamplersPerPipeline", "Reset samplers per pipeline"),
            Map.entry("nativeaccelerator.options.forceRenderAreaScissor", "Force render-area scissor"),
            Map.entry("nativeaccelerator.options.mac1RetainTextureTransfers", "Retain Mac1 texture uploads"),
            Map.entry("nativeaccelerator.options.mac1RegionalDepthAttachmentClear", "Safe Mac1 item-atlas depth clear"),
            Map.entry("nativeaccelerator.options.mac1RelaxImplicitPassScissor", "Relax Mac1 pass scissor"),
            Map.entry("nativeaccelerator.options.explicitViewportPerPass", "Reset viewport each pass"),
            Map.entry("nativeaccelerator.options.mac1StrictSwapchainPresent", "Mac1 strict swapchain present"),
            Map.entry("nativeaccelerator.options.mac1WaitForSwapchain", "Mac1 wait before swapchain acquire"),
            Map.entry("nativeaccelerator.options.mac1SerializeBufferUploads", "Mac1 serialize buffer uploads"),
            Map.entry("nativeaccelerator.options.mac1ForcePresentedSourceFirstClear", "Mac1 clear stale presented target"),
            Map.entry("nativeaccelerator.menu.display", "Display"),
            Map.entry("nativeaccelerator.menu.quality", "Quality"),
            Map.entry("nativeaccelerator.menu.preferences", "Preferences"),
            Map.entry("nativeaccelerator.menu.native", "Native Accelerator")
    );

    private static final Map<String, String> ES = Map.ofEntries(
            Map.entry("nativeaccelerator.options.renderer", "Backend gráfico"),
            Map.entry("nativeaccelerator.options.renderer.tooltip", "Selecciona el backend gráfico que se usará después de reiniciar."),
            Map.entry("nativeaccelerator.graphicsApi.default", "Automático"),
            Map.entry("nativeaccelerator.graphicsApi.opengl", "SGI OpenGL"),
            Map.entry("nativeaccelerator.graphicsApi.vulkan", "Vulkan"),
            Map.entry("nativeaccelerator.graphicsApi.metal", "Metal"),
            Map.entry("nativeaccelerator.options.graphics.header", "Native Accelerator"),
            Map.entry("nativeaccelerator.options.graphics.pipeline", "Renderizador / atlas"),
            Map.entry("nativeaccelerator.options.restart.tooltip", "Guardado en minecraft/etc/nativeaccelerator.properties. La mayoría de los cambios se aplican tras reiniciar."),
            Map.entry("nativeaccelerator.options.auto", "Automático"),
            Map.entry("nativeaccelerator.options.unixTheme", "Tema CDE / Motif"),
            Map.entry("nativeaccelerator.options.crtScanlines", "Líneas CRT"),
            Map.entry("nativeaccelerator.options.showUma", "Mostrar Uma"),
            Map.entry("nativeaccelerator.options.umaOpacity", "Opacidad de Uma"),
            Map.entry("nativeaccelerator.options.nativeVulkan", "Vulkan nativo"),
            Map.entry("nativeaccelerator.options.rendererWorkers", "Hilos del renderizador"),
            Map.entry("nativeaccelerator.options.fastStitcher", "Stitcher rápido"),
            Map.entry("nativeaccelerator.options.safeFastStitcher", "Stitcher seguro"),
            Map.entry("nativeaccelerator.options.verifyFastStitcher", "Verificar stitcher"),
            Map.entry("nativeaccelerator.options.parallelMipmaps", "Mipmaps paralelos"),
            Map.entry("nativeaccelerator.options.atlasWorkers", "Hilos del atlas"),
            Map.entry("nativeaccelerator.options.decodedTextureCache", "Caché de texturas"),
            Map.entry("nativeaccelerator.options.atlasLayoutCache", "Caché del atlas"),
            Map.entry("nativeaccelerator.options.requireVerifiedAtlas", "Solo atlas verificado"),
            Map.entry("nativeaccelerator.options.guiNineSliceFix", "Corrección nine-slice"),
            Map.entry("nativeaccelerator.options.metalFrames", "Frames Metal en vuelo"),
            Map.entry("nativeaccelerator.options.metalCpuIndirect", "Indirecto CPU Metal"),
            Map.entry("nativeaccelerator.options.metalYFlip", "Y-flip generado Metal"),
            Map.entry("nativeaccelerator.options.mac1Frames", "Frames Mac1 en vuelo"),
            Map.entry("nativeaccelerator.options.mac1CycleClearedTargets", "Ciclo seguro de destinos Mac1"),
            Map.entry("nativeaccelerator.options.guiRegionalClearUpload", "Limpieza regional GUI por carga"),
            Map.entry("nativeaccelerator.options.resetSamplersPerPipeline", "Reiniciar samplers por pipeline"),
            Map.entry("nativeaccelerator.options.forceRenderAreaScissor", "Forzar tijera del área de render"),
            Map.entry("nativeaccelerator.options.mac1RetainTextureTransfers", "Retener cargas de textura Mac1"),
            Map.entry("nativeaccelerator.options.mac1RegionalDepthAttachmentClear", "Limpieza segura de profundidad del atlas Mac1"),
            Map.entry("nativeaccelerator.options.mac1RelaxImplicitPassScissor", "Relajar tijera de pase Mac1"),
            Map.entry("nativeaccelerator.options.explicitViewportPerPass", "Restablecer viewport por pase"),
            Map.entry("nativeaccelerator.options.mac1StrictSwapchainPresent", "Presentación estricta Mac1"),
            Map.entry("nativeaccelerator.options.mac1WaitForSwapchain", "Esperar swapchain Mac1 antes de adquirir"),
            Map.entry("nativeaccelerator.options.mac1SerializeBufferUploads", "Serializar cargas de búfer Mac1"),
            Map.entry("nativeaccelerator.options.mac1ForcePresentedSourceFirstClear", "Limpiar destino presentado obsoleto Mac1"),
            Map.entry("nativeaccelerator.menu.display", "Pantalla"),
            Map.entry("nativeaccelerator.menu.quality", "Calidad"),
            Map.entry("nativeaccelerator.menu.preferences", "Preferencias"),
            Map.entry("nativeaccelerator.menu.native", "Native Accelerator")
    );

    private GraphicsMenuText() {}

    public static Component component(Options options, String key) {
        return Component.literal(text(options, key));
    }

    public static String text(Options options, String key) {
        boolean spanish = options != null && options.languageCode != null
                && options.languageCode.toLowerCase(Locale.ROOT).startsWith("es_");
        return (spanish ? ES : EN).getOrDefault(key, EN.getOrDefault(key, key));
    }
}

