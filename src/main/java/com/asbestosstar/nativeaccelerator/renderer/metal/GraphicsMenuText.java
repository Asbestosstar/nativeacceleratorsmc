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
