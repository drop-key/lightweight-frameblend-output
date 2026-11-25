package net.frameblend.output.config;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.frameblend.output.recording.RecordingShaderManager;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ConfigManager {
    private static final List<String> errorMessages = new ArrayList<>();
    private static boolean configReset;
    private static ConfigEntries config;

    public static ConfigEntries getConfig() {
        if (config == null) loadConfig();
        return config;
    }

    public static Screen createConfigScreen(Screen parent) { return new OutputConfigScreen(parent, getConfig()); }

    public static void openConfigScreen() {
        Minecraft.getInstance().schedule(() -> Minecraft.getInstance().gui.setScreen(createConfigScreen(null)));
    }

    static void applySettings(boolean enabled, int fps, boolean enableOnStartup) {
        if (fps < 24 || fps > 240) throw new IllegalArgumentException("Output FPS must be between 24 and 240");
        ConfigEntries current = getConfig();
        boolean resetExposure = current.recordingOverlayEnabled != enabled || current.recordingOverlayTargetFPS != fps;
        current.recordingOverlayEnabled = enabled;
        current.recordingOverlayTargetFPS = fps;
        current.enableOnStartup = enableOnStartup;
        saveConfig();
        if (resetExposure) Minecraft.getInstance().execute(RecordingShaderManager::pauseOutput);
    }

    public static void loadConfig() {
        File file = getConfigFile();
        errorMessages.clear();
        configReset = false;
        config = new ConfigEntries();
        if (!file.exists()) {
            File previous = findPreviousConfig(file);
            if (previous != null) {
                try { FileUtils.copyFile(previous, file); }
                catch (IOException failure) { throw new RuntimeException("Could not migrate Frameblend Output preferences", failure); }
            }
        }
        if (!file.exists()) { saveConfig(); return; }
        try {
            var loaded = ConfigCodec.read(FileUtils.readFileToString(file, StandardCharsets.UTF_8));
            config = loaded.config();
            errorMessages.addAll(loaded.errors());
            configReset = !errorMessages.isEmpty();
            if (loaded.rewrite()) saveConfig();
        } catch (Exception invalid) {
            config = new ConfigEntries();
            saveConfig();
            configReset = true;
            errorMessages.add("Frameblend Output: the configuration could not be loaded and was reset to defaults.");
        }
    }

    public static void saveConfig() {
        try { FileUtils.write(getConfigFile(), ConfigCodec.write(getConfig()), StandardCharsets.UTF_8); }
        catch (IOException failure) { throw new RuntimeException("Could not save Frameblend Output settings", failure); }
    }

    private static File getConfigFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("frameblendoutput.json").toFile();
    }

    // Recognize the previous schema by its unique setting, without retaining
    // an obsolete product identifier or changing the previous file.
    static File findPreviousConfig(File destination) {
        File[] candidates = destination.getParentFile().listFiles(f -> f.isFile() && f.getName().endsWith(".json") && f.length() < 65536);
        if (candidates == null) return null;
        java.util.Arrays.sort(candidates, java.util.Comparator.comparingLong(File::lastModified).reversed());
        for (File candidate : candidates) {
            try {
                var json = com.google.gson.JsonParser.parseString(FileUtils.readFileToString(candidate, StandardCharsets.UTF_8));
                if (json.isJsonObject() && json.getAsJsonObject().has("recordingOverlayTargetFPS")) return candidate;
            } catch (RuntimeException | IOException ignored) { }
        }
        return null;
    }

    public static boolean isConfigReset() { return configReset; }

    public static List<String> getAndClearErrorMessages() {
        var messages = new ArrayList<>(errorMessages);
        errorMessages.clear();
        return messages;
    }
}
