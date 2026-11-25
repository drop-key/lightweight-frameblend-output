package net.frameblend.output.recording;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.junit.Test;

import static org.junit.Assert.*;

public class RecordingPipelineFormatsTest {
    @Test
    public void blendPipelinesMatchTheirFloatAttachments() {
        for (String name : new String[]{"frame_blending", "frame_blending_gl"}) {
            RenderPipeline pipeline = pipeline("frameblendoutput", "post/" + name);
            assertEquals("Minecraft rejects a float render target with an 8-bit pipeline",
                    GpuFormat.RGBA32_FLOAT, pipeline.getColorTargetState().format());
            assertEquals(RecordingPipelineFormats.BLEND_FORMAT, pipeline.getColorTargetState().format());
            assertTrue(pipeline.getColorTargetState().blendFunction().isEmpty());
        }
    }

    @Test
    public void outputCursorAndUnrelatedPipelinesKeepTheirOriginalFormats() {
        assertEquals(GpuFormat.RGBA8_UNORM, pipeline("frameblendoutput", "post/frame_blending_direct").getColorTargetState().format());
        assertEquals(GpuFormat.RGBA8_UNORM, pipeline("frameblendoutput", "post/frame_blending_direct_gl").getColorTargetState().format());
        assertEquals(GpuFormat.RGBA8_UNORM, pipeline("frameblendoutput", "post/frame_resolve").getColorTargetState().format());
        assertEquals(GpuFormat.RGBA8_UNORM, pipeline("frameblendoutput", "post/cursor_overlay").getColorTargetState().format());
        assertEquals(GpuFormat.RGBA8_UNORM, pipeline("minecraft", "post/blit").getColorTargetState().format());
        assertEquals(GpuFormat.RGBA8_UNORM, pipeline("another_mod", "post/frame_blending").getColorTargetState().format());
    }

    private static RenderPipeline pipeline(String namespace, String path) {
        Identifier shader = Identifier.fromNamespaceAndPath(namespace, path);
        return RecordingPipelineFormats.configure(RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET), shader)
                .withLocation(shader).withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
                .withFragmentShader(shader).build();
    }
}
