package net.frameblend.output.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/** Saved preferences and migration, independent of the renderer's lifecycle. */
final class ConfigCodec {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    record Loaded(ConfigEntries config, boolean rewrite, List<String> errors) { }

    static Loaded read(String text) {
        var parsed = JsonParser.parseString(text);
        ConfigEntries config = new ConfigEntries();
        if (parsed.isJsonNull()) return new Loaded(config, true, List.of());
        JsonObject json = parsed.getAsJsonObject();
        var errors = new ArrayList<String>();
        boolean rewrite = !json.has("recordingOverlayTargetFPS") || !json.has("enableOnStartup")
                || json.keySet().stream().anyMatch(key -> !key.equals("recordingOverlayTargetFPS") && !key.equals("enableOnStartup"));
        if (json.has("recordingOverlayTargetFPS")) {
            try {
                int fps = json.get("recordingOverlayTargetFPS").getAsBigDecimal().intValueExact();
                if (fps < 24 || fps > 240) throw new IllegalArgumentException();
                config.recordingOverlayTargetFPS = fps;
            } catch (RuntimeException invalid) {
                errors.add("Frameblend Output: invalid output FPS was reset to 60.");
                rewrite = true;
            }
        }
        if (json.has("enableOnStartup")) {
            var startup = json.get("enableOnStartup");
            if (startup.isJsonPrimitive() && startup.getAsJsonPrimitive().isBoolean()) {
                config.enableOnStartup = startup.getAsBoolean();
            } else {
                errors.add("Frameblend Output: invalid startup preference was reset to disabled.");
                rewrite = true;
            }
        }
        config.recordingOverlayEnabled = config.enableOnStartup;
        return new Loaded(config, rewrite, List.copyOf(errors));
    }

    static String write(ConfigEntries config) { return GSON.toJson(config); }
}
