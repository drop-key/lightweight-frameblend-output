package net.frameblend.output.config;

import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigCodecTest {
    @Test public void nextLaunchUsesSavedPreferenceInsteadOfCurrentSessionToggle() {
        for (boolean startup : new boolean[]{false, true}) {
            ConfigEntries current = new ConfigEntries();
            current.enableOnStartup = startup;
            current.recordingOverlayEnabled = !startup;
            current.recordingOverlayTargetFPS = 144;
            String saved = ConfigCodec.write(current);
            assertFalse(JsonParser.parseString(saved).getAsJsonObject().has("recordingOverlayEnabled"));
            var loaded = ConfigCodec.read(saved);
            assertEquals(startup, loaded.config().enableOnStartup);
            assertEquals(startup, loaded.config().recordingOverlayEnabled);
            assertEquals(144, loaded.config().recordingOverlayTargetFPS);
            assertFalse(loaded.rewrite());
            assertTrue(loaded.errors().isEmpty());
        }
    }

    @Test public void olderConfigurationsKeepFpsAndMigrateToManualStartup() {
        var loaded = ConfigCodec.read("{\"recordingOverlayTargetFPS\":120,\"recordingOverlayEnabled\":true,\"velocityBlur\":true}");
        assertEquals(120, loaded.config().recordingOverlayTargetFPS);
        assertFalse(loaded.config().enableOnStartup);
        assertFalse(loaded.config().recordingOverlayEnabled);
        assertTrue(loaded.rewrite());
        assertTrue(loaded.errors().isEmpty());
        var saved = JsonParser.parseString(ConfigCodec.write(loaded.config())).getAsJsonObject();
        assertEquals(2, saved.size());
        assertFalse(saved.has("velocityBlur"));
    }

    @Test public void invalidPreferencesResetIndependently() {
        for (String invalid : new String[]{"null", "\"true\"", "[]", "{}", "1"}) {
            var loaded = ConfigCodec.read("{\"recordingOverlayTargetFPS\":120,\"enableOnStartup\":" + invalid + "}");
            assertEquals(120, loaded.config().recordingOverlayTargetFPS);
            assertFalse(loaded.config().recordingOverlayEnabled);
            assertEquals(1, loaded.errors().size());
            assertTrue(loaded.rewrite());
        }
        for (String invalid : new String[]{"null", "23", "241", "60.5", "[]"}) {
            var loaded = ConfigCodec.read("{\"recordingOverlayTargetFPS\":" + invalid + ",\"enableOnStartup\":true}");
            assertEquals(60, loaded.config().recordingOverlayTargetFPS);
            assertTrue(loaded.config().recordingOverlayEnabled);
            assertEquals(1, loaded.errors().size());
        }
    }

    @Test public void defaultsAndFpsLimitsAreRetained() {
        var defaults = ConfigCodec.read("{}");
        assertFalse(defaults.config().recordingOverlayEnabled);
        assertFalse(defaults.config().enableOnStartup);
        assertEquals(60, defaults.config().recordingOverlayTargetFPS);
        for (int fps : new int[]{24, 240}) {
            var loaded = ConfigCodec.read("{\"recordingOverlayTargetFPS\":" + fps + ",\"enableOnStartup\":false}");
            assertEquals(fps, loaded.config().recordingOverlayTargetFPS);
            assertFalse(loaded.rewrite());
            assertTrue(loaded.errors().isEmpty());
        }
    }
}
