package net.frameblend.output.config;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ConfigMigrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void schemaDetectionFindsPreferencesWithoutDependingOnThePreviousBrand() throws Exception {
        var directory = temporary.getRoot().toPath();
        Files.writeString(directory.resolve("other.json"), "{\"enabled\":true}");
        Files.writeString(directory.resolve("broken.json"), "not json");
        var preferences = directory.resolve("previous-settings.json");
        Files.writeString(preferences, "{\"recordingOverlayTargetFPS\":120,\"enableOnStartup\":true}");
        var match = ConfigManager.findPreviousConfig(directory.resolve("frameblendoutput.json").toFile());
        assertEquals(preferences.toFile(), match);
        var loaded = ConfigCodec.read(Files.readString(match.toPath())).config();
        assertEquals(120, loaded.recordingOverlayTargetFPS);
        assertTrue(loaded.recordingOverlayEnabled);
        assertTrue(Files.exists(preferences));
    }
}
