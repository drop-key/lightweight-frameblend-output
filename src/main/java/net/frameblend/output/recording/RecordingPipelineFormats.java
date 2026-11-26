package net.frameblend.output.recording;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/** Declares the attachment format before Minecraft compiles a recording pipeline. */
public final class RecordingPipelineFormats {
    public static final GpuFormat BLEND_FORMAT = GpuFormat.RGBA32_FLOAT;

    private RecordingPipelineFormats() {}

    public static RenderPipeline.Builder configure(RenderPipeline.Builder builder, Identifier fragmentShader) {
        if (fragmentShader.getNamespace().equals("frameblendoutput")
                && (fragmentShader.getPath().equals("post/frame_blending")
                || fragmentShader.getPath().equals("post/frame_blending_gl"))) {
            builder.withColorTargetState(0, new ColorTargetState(Optional.empty(), BLEND_FORMAT, ColorTargetState.WRITE_ALL));
        }
        return builder;
    }
}
