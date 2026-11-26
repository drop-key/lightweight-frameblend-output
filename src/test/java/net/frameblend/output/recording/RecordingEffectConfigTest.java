package net.frameblend.output.recording;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.resources.Identifier;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class RecordingEffectConfigTest {
    @Test
    public void singlePassEffectsDecodeWithMinecraftCodec() throws Exception {
        for (String name : new String[]{"frame_blending", "frame_blending_gl", "frame_resolve", "frame_blending_direct", "frame_blending_direct_gl"}) {
            Path path = Path.of("src/main/resources/assets/frameblendoutput/post_effect", name + ".json");
            PostChainConfig config = PostChainConfig.CODEC.parse(JsonOps.INSTANCE,
                    JsonParser.parseString(Files.readString(path))).getOrThrow();
            assertTrue(config.internalTargets().isEmpty());
            assertEquals(1, config.passes().size());
            assertEquals(Identifier.fromNamespaceAndPath("frameblendoutput", "recording_output"),
                    config.passes().getFirst().outputTarget());
        }
    }
}
