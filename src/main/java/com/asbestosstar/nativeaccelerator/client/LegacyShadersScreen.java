package com.asbestosstar.nativeaccelerator.client;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackCatalog;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackDescriptor;
import com.asbestosstar.nativeaccelerator.renderer.universal.ShaderpackPostProcessRuntime;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackRuntime;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Shaderpack selection UI. Saved selection is a request, not proof of live shader execution. */
public final class LegacyShadersScreen extends Screen {
    private static final int MAX_PAGE_SIZE = 7;
    private final Screen parent;
    private final ShaderpackCatalog catalog = new ShaderpackCatalog();
    private List<ShaderpackDescriptor> packs = List.of();
    private String draftId;
    private String messageKey = "nativeaccelerator.shaderpacks.loading";
    private String messageArgument = "";
    private boolean loading;
    private int page;
    private int footerHeight;

    public LegacyShadersScreen(Screen parent) {
        super(Component.translatable("nativeaccelerator.legacyShaders"));
        this.parent = parent;
        this.draftId = catalog.selectedId();
    }

    @Override
    protected void init() {
        buildWidgets();
        if (packs.isEmpty() && !loading && messageKey.equals("nativeaccelerator.shaderpacks.loading")) {
            scan();
        }
    }

    private void scan() {
        loading = true;
        messageKey = "nativeaccelerator.shaderpacks.loading";
        buildWidgets();
        CompletableFuture.supplyAsync(() -> {
            try {
                catalog.ensureRoot();
                return catalog.scan();
            } catch (IOException failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }).whenComplete((result, failure) -> minecraft.execute(() -> {
            loading = false;
            if (failure != null) {
                packs = List.of();
                messageKey = "nativeaccelerator.shaderpacks.scanError";
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                messageArgument = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            } else {
                packs = result;
                int match = indexOfDraft();
                if (match >= 0) page = match / pageSize();
                messageKey = packs.isEmpty() ? "nativeaccelerator.shaderpacks.empty" : "nativeaccelerator.shaderpacks.ready";
                messageArgument = "";
            }
            buildWidgets();
        }));
    }

    private void buildWidgets() {
        clearWidgets();
        int listWidth = Math.max(1, Math.min(360, width - 32));
        int left = (width - listWidth) / 2;
        int pageSize = pageSize();
        int row = 66;
        int end = Math.min(packs.size(), (page + 1) * pageSize);
        for (int i = page * pageSize; i < end; i++) {
            ShaderpackDescriptor pack = packs.get(i);
            boolean isSelected = pack.id().equals(draftId);
            Component label = Component.translatable(isSelected
                    ? "nativeaccelerator.shaderpacks.rowSelected" : "nativeaccelerator.shaderpacks.row",
                    pack.displayName(), statusText(pack));
            addRenderableWidget(Button.builder(label, button -> {
                draftId = pack.id();
                messageKey = "nativeaccelerator.shaderpacks.selectionDraft";
                messageArgument = pack.displayName();
                buildWidgets();
            }).bounds(left, row, listWidth, 20).build());
            row += 23;
        }

        Component[] footerLabels = {
                Component.translatable("nativeaccelerator.shaderpacks.settings"),
                Component.translatable("nativeaccelerator.shaderpacks.previous"),
                Component.translatable("nativeaccelerator.shaderpacks.next"),
                Component.translatable("nativeaccelerator.shaderpacks.none"),
                Component.translatable("nativeaccelerator.shaderpacks.refresh"),
                Component.translatable("nativeaccelerator.shaderpacks.apply"),
                Component.translatable("nativeaccelerator.shaderpacks.ok"),
                Component.translatable("nativeaccelerator.shaderpacks.cancel")
        };
        int[] widths = new int[footerLabels.length];
        for (int i = 0; i < footerLabels.length; i++) widths[i] = Math.max(58, font.width(footerLabels[i]) + 20);
        LegacyShadersFooterLayout.Group measuredFooter = LegacyShadersFooterLayout.layout(
                width, 0, widths, 20, 6, 4);
        LegacyShadersFooterLayout.Group footer = LegacyShadersFooterLayout.layout(
                width, Math.max(0, height - measuredFooter.height()), widths, 18, 6, 3);
        footerHeight = footer.height();
        List<LegacyShadersFooterLayout.Rect> bounds = footer.buttons();
        int selectedIndex = indexOfDraft();

        Button settings = Button.builder(footerLabels[0], button -> {
            ShaderpackDescriptor selected = selectedPack();
            if (selected != null) minecraft.gui.setScreen(new ShaderpackSettingsScreen(this, catalog, selected));
        }).bounds(rect(bounds, 0).x(), rect(bounds, 0).y(), rect(bounds, 0).width(), rect(bounds, 0).height()).build();
        settings.active = selectedIndex >= 0 && !loading;
        addRenderableWidget(settings);

        Button previous = Button.builder(footerLabels[1], button -> {
            page = Math.max(0, page - 1);
            buildWidgets();
        }).bounds(rect(bounds, 1).x(), rect(bounds, 1).y(), rect(bounds, 1).width(), rect(bounds, 1).height()).build();
        previous.active = page > 0 && !loading;
        addRenderableWidget(previous);
        Button next = Button.builder(footerLabels[2], button -> {
            page = Math.min(Math.max(0, (packs.size() - 1) / pageSize()), page + 1);
            buildWidgets();
        }).bounds(rect(bounds, 2).x(), rect(bounds, 2).y(), rect(bounds, 2).width(), rect(bounds, 2).height()).build();
        next.active = (page + 1) * pageSize() < packs.size() && !loading;
        addRenderableWidget(next);

        Button vanilla = Button.builder(footerLabels[3], button -> {
            draftId = "";
            messageKey = "nativeaccelerator.shaderpacks.vanillaDraft";
            messageArgument = "";
            buildWidgets();
        }).bounds(rect(bounds, 3).x(), rect(bounds, 3).y(), rect(bounds, 3).width(), rect(bounds, 3).height()).build();
        addRenderableWidget(vanilla);

        Button refresh = Button.builder(footerLabels[4], button -> scan()).bounds(rect(bounds, 4).x(), rect(bounds, 4).y(), rect(bounds, 4).width(), rect(bounds, 4).height()).build();
        refresh.active = !loading;
        addRenderableWidget(refresh);
        Button apply = Button.builder(footerLabels[5], button -> apply()).bounds(rect(bounds, 5).x(), rect(bounds, 5).y(), rect(bounds, 5).width(), rect(bounds, 5).height()).build();
        apply.active = !loading;
        addRenderableWidget(apply);
        Button ok = Button.builder(footerLabels[6], button -> {
            apply();
            onClose();
        }).bounds(rect(bounds, 6).x(), rect(bounds, 6).y(), rect(bounds, 6).width(), rect(bounds, 6).height()).build();
        ok.active = !loading;
        addRenderableWidget(ok);
        addRenderableWidget(Button.builder(footerLabels[7], button -> onClose()).bounds(rect(bounds, 7).x(), rect(bounds, 7).y(), rect(bounds, 7).width(), rect(bounds, 7).height()).build());
    }

    private int pageSize() {
        int[] approximateWidths = {72, 64, 64, 92, 78, 68, 58, 72};
        int footerRows = Math.max(1, LegacyShadersFooterLayout.rowCount(width, approximateWidths, 6));
        int footerHeight = footerRows * 20 + Math.max(0, footerRows - 1) * 4;
        int listSpace = Math.max(23, height - 31 - 72 - footerHeight - 52);
        return Math.max(1, Math.min(MAX_PAGE_SIZE, listSpace / 23));
    }

    private static LegacyShadersFooterLayout.Rect rect(List<LegacyShadersFooterLayout.Rect> bounds, int index) {
        return bounds.get(index);
    }

    private Component statusText(ShaderpackDescriptor pack) {
        return Component.translatable("nativeaccelerator.shaderpacks.status." + switch (pack.status()) {
            case FRONTEND_ONLY -> "frontendOnly";
            case INCOMPLETE -> "incomplete";
            case UNSUPPORTED -> "unsupported";
            case INVALID -> "invalid";
            case UNKNOWN -> "unknown";
            case EXECUTABLE -> "executable";
            case BLOCKED -> "blocked";
        });
    }

    private ShaderpackDescriptor selectedPack() {
        return packs.stream().filter(pack -> pack.id().equals(draftId)).findFirst().orElse(null);
    }

    private int indexOfDraft() {
        for (int i = 0; i < packs.size(); i++) if (packs.get(i).id().equals(draftId)) return i;
        return -1;
    }

    private void apply() {
        catalog.select(draftId);
        ShaderpackDescriptor selected = selectedPack();
        boolean executable = selected != null && selected.status().executable();
        NativeAcceleratorConfig.setBoolean("shaders.enabled", executable);
        messageKey = executable ? "nativeaccelerator.shaderpacks.saved" : "nativeaccelerator.shaderpacks.savedNotActive";
        messageArgument = selected == null ? "" : selected.displayName();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractTransparentBackground(graphics);
        int panelWidth = Math.max(1, Math.min(370, width - 16));
        int panelLeft = (width - panelWidth) / 2;
        graphics.fill(panelLeft, 31, panelLeft + panelWidth, 55, 0xAA16121D);
        graphics.text(font, Component.translatable(messageKey, messageArgument), panelLeft + 7, 39, 0xFFFFFFFF);
        ShaderpackDescriptor selected = selectedPack();
        if (selected != null) {
            graphics.fill(panelLeft, 57, panelLeft + panelWidth, 63, selected.status().executable() ? 0xFF61C98A : 0xFFE4B45E);
            graphics.text(font, Component.translatable("nativeaccelerator.shaderpacks.selected", selected.displayName()),
                    panelLeft + 7, 58, 0xFF17131A);
            graphics.text(font, Component.translatable("nativeaccelerator.shaderpacks.reason", Component.literal(selected.reason())),
                    panelLeft + 7, Math.min(height - footerHeight - 42, 67 + pageSize() * 23), 0xFFEEEAF2);
        }
        ShaderpackRuntime.AttemptSnapshot attempt = ShaderpackPostProcessRuntime.runtime().attemptSnapshot();
        if (attempt.state() != ShaderpackRuntime.AttemptState.NOT_ATTEMPTED) {
            int statusY = height - footerHeight - 36;
            Component runtimeStatus = switch (attempt.state()) {
                case PREPARING -> Component.translatable("nativeaccelerator.shaderpacks.runtime.preparing", attempt.packId());
                case ACTIVE -> Component.translatable("nativeaccelerator.shaderpacks.runtime.active", attempt.packId());
                case FAILED -> Component.translatable("nativeaccelerator.shaderpacks.runtime.failed", attempt.packId(),
                        String.join("; ", attempt.diagnostics()));
                case NOT_ATTEMPTED -> Component.empty();
            };
            graphics.text(font, runtimeStatus, panelLeft + 7, statusY, 0xFFFFC27A);
        }
        if (loading) {
            int barLeft = width / 2 - 90;
            int barTop = height / 2;
            graphics.fill(barLeft, barTop, barLeft + 180, barTop + 8, 0xFF393441);
            int segment = (int) ((System.currentTimeMillis() / 8) % 180);
            graphics.fill(barLeft + Math.max(0, segment - 35), barTop, barLeft + segment, barTop + 8, 0xFFB98AAE);
        }
    }
}
