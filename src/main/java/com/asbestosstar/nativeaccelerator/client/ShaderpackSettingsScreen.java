package com.asbestosstar.nativeaccelerator.client;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSource;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderPropertiesParser;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderPropertiesProfile;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackCatalog;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackDescriptor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** UI for safe, pack-declared options. Values are draft-only until Apply or OK. */
public final class ShaderpackSettingsScreen extends Screen {
    private static final int MAX_PAGE_SIZE = 8;
    private final Screen parent;
    private final ShaderpackDescriptor pack;
    private final ShaderpackCatalog catalog;
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, ShaderPropertiesProfile.ShaderOption> options = new LinkedHashMap<>();
    private String messageKey = "";
    private String messageArgument = "";
    private int page;

    public ShaderpackSettingsScreen(Screen parent, ShaderpackCatalog catalog, ShaderpackDescriptor pack) {
        super(Component.translatable("nativeaccelerator.shaderpacks.settings.title", pack.displayName()));
        this.parent = parent;
        this.catalog = catalog;
        this.pack = pack;
    }

    @Override
    protected void init() {
        if (options.isEmpty() && messageKey.isEmpty()) loadOptions();
        buildWidgets();
    }

    private void loadOptions() {
        try (ShaderPackSource source = catalog.openSource(pack)) {
            String path = (pack.conventionalProfile().root().isEmpty()
                    ? "" : pack.conventionalProfile().root() + "/") + "shaders.properties";
            String text = source.readText(path);
            if (text == null) {
                messageKey = "nativeaccelerator.shaderpacks.settings.empty";
                return;
            }
            ShaderPropertiesProfile profile = ShaderPropertiesParser.parse(text, Set.of());
            if (!profile.diagnostics().isEmpty()) {
                messageKey = "nativeaccelerator.shaderpacks.settings.metadataWarning";
                messageArgument = profile.diagnostics().get(0);
            }
            profile.options().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                ShaderPropertiesProfile.ShaderOption option = entry.getValue();
                if (option.values().isEmpty()) return;
                String name = entry.getKey();
                options.put(name, option);
                String defaultValue = option.values().contains(option.defaultValue())
                        ? option.defaultValue() : option.values().get(0);
                String saved = NativeAcceleratorConfig.stringValue(settingKey(name), "");
                values.put(name, option.values().contains(saved) ? saved : defaultValue);
            });
            if (options.isEmpty() && messageKey.isEmpty()) messageKey = "nativeaccelerator.shaderpacks.settings.empty";
        } catch (IOException | RuntimeException failure) {
            messageKey = "nativeaccelerator.shaderpacks.settings.readError";
            messageArgument = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        }
    }

    private void buildWidgets() {
        clearWidgets();
        int left = Math.max(16, width / 2 - 180);
        int row = 66;
        int pageSize = pageSize();
        List<Map.Entry<String, ShaderPropertiesProfile.ShaderOption>> entries = new ArrayList<>(options.entrySet());
        int end = Math.min(entries.size(), (page + 1) * pageSize);
        for (int i = page * pageSize; i < end; i++) {
            var entry = entries.get(i);
            String name = entry.getKey();
            ShaderPropertiesProfile.ShaderOption option = entry.getValue();
            String display = option.displayName().isBlank() ? name : option.displayName();
            Button control = Button.builder(optionLabel(display, values.get(name)), button -> {
                List<String> choices = option.values();
                int current = choices.indexOf(values.get(name));
                String next = choices.get((current + 1 + choices.size()) % choices.size());
                values.put(name, next);
                button.setMessage(optionLabel(display, next));
            }).bounds(left, row, 360, 20).build();
            addRenderableWidget(control);
            row += 23;
        }
        int footer = height - 45;
        Button previous = Button.builder(Component.translatable("nativeaccelerator.shaderpacks.previous"), button -> {
            page = Math.max(0, page - 1);
            buildWidgets();
        }).bounds(width / 2 - 170, footer - 24, 64, 20).build();
        previous.active = page > 0;
        addRenderableWidget(previous);
        Button next = Button.builder(Component.translatable("nativeaccelerator.shaderpacks.next"), button -> {
            page = Math.min(Math.max(0, (entries.size() - 1) / pageSize()), page + 1);
            buildWidgets();
        }).bounds(width / 2 + 106, footer - 24, 64, 20).build();
        next.active = (page + 1) * pageSize() < entries.size();
        addRenderableWidget(next);

        addRenderableWidget(Button.builder(Component.translatable("nativeaccelerator.shaderpacks.apply"), button -> {
            persist();
            messageKey = "nativeaccelerator.shaderpacks.settings.saved";
        }).bounds(width / 2 - 160, footer, 76, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("nativeaccelerator.shaderpacks.ok"), button -> {
            persist();
            onClose();
        }).bounds(width / 2 - 78, footer, 76, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("nativeaccelerator.shaderpacks.cancel"), button -> onClose())
                .bounds(width / 2 + 4, footer, 76, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("nativeaccelerator.shaderpacks.back"), button -> onClose())
                .bounds(width / 2 + 86, footer, 76, 20).build());
    }

    private int pageSize() {
        return Math.max(1, Math.min(MAX_PAGE_SIZE, (height - 150) / 23));
    }

    private void persist() {
        values.forEach((key, value) -> NativeAcceleratorConfig.setString(settingKey(key), value));
    }

    private static Component optionLabel(String name, String value) {
        return Component.translatable("nativeaccelerator.shaderpacks.settings.option", name, value);
    }

    private String settingKey(String option) {
        String hash = pack.contentHash().isBlank() ? "unknown" : pack.contentHash();
        String safePack = pack.id().replaceAll("[^A-Za-z0-9_-]", "_");
        String safeOption = option.replaceAll("[^A-Za-z0-9_-]", "_");
        return "shaderpack.option." + safePack + "." + hash + "." + safeOption;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractTransparentBackground(graphics);
        graphics.fill(width / 2 - 185, 31, width / 2 + 185, 55, 0xAA16121D);
        if (!messageKey.isBlank()) {
            graphics.text(font, Component.translatable(messageKey, messageArgument), width / 2 - 178, 39, 0xFFFFFFFF);
        }
        graphics.text(font, Component.translatable("nativeaccelerator.shaderpacks.settings.notice"),
                Math.max(12, width / 2 - 178), height - 62, 0xFFEEEAF2);
    }
}
