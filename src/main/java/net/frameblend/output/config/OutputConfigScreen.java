package net.frameblend.output.config;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.glfw.GLFW;

/** A compact settings panel with no external links or configuration-library UI. */
final class OutputConfigScreen extends Screen {
    private static final int PANEL_HEIGHT = 218;
    private static final int RESET_WIDTH = 40;
    private final ConfigEntries defaults = new ConfigEntries();
    private final Screen parent;
    private boolean enabled, startup;
    private int fps;
    private int left, top, panelWidth, controlsX, controlsWidth;

    OutputConfigScreen(Screen parent, ConfigEntries current) {
        super(Component.literal("Lightweight Frameblend Output"));
        this.parent = parent;
        enabled = current.recordingOverlayEnabled;
        startup = current.enableOnStartup;
        fps = current.recordingOverlayTargetFPS;
    }

    @Override protected void init() {
        panelWidth = Math.min(400, width - 24);
        left = (width - panelWidth) / 2;
        top = Math.max(6, (height - PANEL_HEIGHT) / 2);
        controlsWidth = Math.min(120, panelWidth / 3);
        controlsX = left + panelWidth - 16 - RESET_WIDTH - 6 - controlsWidth;

        var outputButton = addRenderableWidget(new FlatButton(controlsX, top + 64, controlsWidth, status(enabled), "OBS output", button -> {
            enabled = !enabled;
            button.setMessage(status(enabled));
        }));
        outputButton.setTooltip(Tooltip.create(Component.literal("Enable or disable the OBS feed for this session.")));
        var fpsSlider = addRenderableWidget(new FpsSlider(controlsX, top + 102, controlsWidth));
        var startupButton = addRenderableWidget(new FlatButton(controlsX, top + 140, controlsWidth, status(startup), "Enable on startup", button -> {
            startup = !startup;
            button.setMessage(status(startup));
        }));
        startupButton.setTooltip(Tooltip.create(Component.literal("Choose whether output starts enabled the next time Minecraft launches.")));

        addResetButton(64, "OBS output", "Disabled", () -> {
            enabled = defaults.recordingOverlayEnabled;
            outputButton.setMessage(status(enabled));
        });
        addResetButton(102, "Output FPS", "60 FPS", fpsSlider::resetToDefault);
        addResetButton(140, "Enable on startup", "Disabled", () -> {
            startup = defaults.enableOnStartup;
            startupButton.setMessage(status(startup));
        });

        int footerWidth = (panelWidth - 40) / 2;
        addRenderableWidget(new FlatButton(left + 16, top + 188, footerWidth, Component.literal("Cancel"), "", button -> onClose()));
        addRenderableWidget(new FlatButton(left + panelWidth - 16 - footerWidth, top + 188, footerWidth,
                Component.literal("Save & Done"), "", button -> {
                    ConfigManager.applySettings(enabled, fps, startup);
                    onClose();
                }));
    }

    private void addResetButton(int y, String label, String defaultValue, Runnable reset) {
        var button = addRenderableWidget(new FlatButton(left + panelWidth - 16 - RESET_WIDTH, top + y, RESET_WIDTH,
                Component.literal("Reset"), label + " default", pressed -> reset.run()));
        button.setTooltip(Tooltip.create(Component.literal("Reset " + label + " to " + defaultValue + ".")));
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(left, top, left + panelWidth, top + PANEL_HEIGHT, 0xF2121419);
        graphics.outline(left, top, panelWidth, PANEL_HEIGHT, 0xFF393D45);
        graphics.text(font, title, left + 16, top + 15, 0xFFF1F2F4, false);
        graphics.text(font, "OBS Spout output", left + 16, top + 30, 0xFF9A9FA9, false);
        graphics.horizontalLine(left + 16, left + panelWidth - 17, top + 50, 0xFF30343C);
        row(graphics, "OBS output", "Blurred video for OBS.", 61);
        row(graphics, "Output FPS", "Match OBS frame rate.", 99);
        row(graphics, "Enable on startup", "Applied at game launch.", 137);
        graphics.horizontalLine(left + 16, left + panelWidth - 17, top + 176, 0xFF30343C);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void row(GuiGraphicsExtractor graphics, String label, String description, int y) {
        graphics.text(font, label, left + 16, top + y, 0xFFE6E8ED, false);
        graphics.text(font, description, left + 16, top + y + 13, 0xFF9A9FA9, false);
    }

    private static Component status(boolean enabled) { return Component.literal(enabled ? "Enabled" : "Disabled"); }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }

    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Lunar may already have requested the frame's one background blur.
        // A plain scrim is compatible with both its wrapper and vanilla.
        graphics.fill(0, 0, width, height, 0x99000000);
    }

    private static final class FlatButton extends Button {
        private final String label;

        FlatButton(int x, int y, int width, Component message, String label, OnPress press) {
            super(x, y, width, 20, message, press, DEFAULT_NARRATION);
            this.label = label;
        }

        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int background = isHoveredOrFocused() ? 0xFF383E49 : 0xFF252A33;
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), background);
            graphics.outline(getX(), getY(), getWidth(), getHeight(), isFocused() ? 0xFFF1F2F4 : 0xFF525965);
            graphics.centeredText(net.minecraft.client.Minecraft.getInstance().font, getMessage(), getX() + getWidth() / 2,
                    getY() + 6, 0xFFF1F2F4);
        }

        @Override protected MutableComponent createNarrationMessage() {
            return Component.literal(label.isEmpty() ? getMessage().getString() : label + ": " + getMessage().getString());
        }
    }

    private final class FpsSlider extends AbstractSliderButton {
        FpsSlider(int x, int y, int width) {
            super(x, y, width, 20, Component.empty(), (fps - 24) / 216.0);
            updateMessage();
            setTooltip(Tooltip.create(Component.literal("Match OBS recording FPS. This changes the blur exposure, not your game FPS.")));
        }

        @Override protected void updateMessage() { setMessage(Component.literal(fps + " FPS")); }
        @Override protected void applyValue() { fps = 24 + (int)Math.round(value * 216); }

        void resetToDefault() {
            fps = defaults.recordingOverlayTargetFPS;
            value = (fps - 24) / 216.0;
            updateMessage();
        }

        @Override public boolean keyPressed(KeyEvent event) {
            if (canChangeValue && (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_RIGHT)) {
                int next = Math.clamp(fps + (event.key() == GLFW.GLFW_KEY_LEFT ? -1 : 1), 24, 240);
                setValue((next - 24) / 216.0);
                return true;
            }
            return super.keyPressed(event);
        }

        @Override protected MutableComponent createNarrationMessage() { return Component.literal("Output FPS: " + fps); }

        @Override public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), isHoveredOrFocused() ? 0xFF383E49 : 0xFF252A33);
            graphics.outline(getX(), getY(), getWidth(), getHeight(), isFocused() ? 0xFFF1F2F4 : 0xFF525965);
            int handle = getX() + 3 + (int)Math.round(value * (getWidth() - 8));
            graphics.fill(getX() + 3, getY() + 16, getX() + getWidth() - 3, getY() + 17, 0xFF525965);
            graphics.fill(handle, getY() + 14, handle + 2, getY() + 19, 0xFFF1F2F4);
            graphics.centeredText(font, getMessage(), getX() + getWidth() / 2, getY() + 4, 0xFFF1F2F4);
        }
    }
}
