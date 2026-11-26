package net.frameblend.output.recording;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import java.util.Optional;

/** Updates the average, output image, and source history in one GPU pass. */
final class RollingExposureRenderer {
    static final int PARAM_SIZE = 32;
    private static final RenderPipeline PIPELINE = createPipeline();
    private static final RenderPipeline SPLIT_PIPELINE = createSplitPipeline(false);
    private static final RenderPipeline SPLIT_SEED_PIPELINE = createSplitPipeline(true);
    private static GpuDevice compiledDevice;
    private static final boolean[] compiled = new boolean[3];
    private static boolean splitEnabled, splitDisabled, seedSplit = true;
    private static RenderTarget splitRGRead, splitRGWrite, splitBRead, splitBWrite;
    private static boolean failed;
    private static boolean loggedSplit;

    static boolean available() { return !failed; }
    static boolean usesSplitState() { return splitEnabled && !splitDisabled; }
    static void rebased() { seedSplit = true; }

    static RenderPipeline createSplitPipeline(boolean seed) {
        BindGroupLayout.Builder bindings = BindGroupLayout.builder();
        if (seed) bindings.withSampler("PreviousSampler");
        else bindings.withSampler("PreviousRGSampler").withSampler("PreviousBSampler");
        bindings.withSampler("CurrentSampler");
        for (int i = 0; i < RollingExposure.MAX_REMOVALS; i++) bindings.withSampler("Removed" + i + "Sampler");
        var builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("frameblendoutput", seed ? "rolling_exposure_split/seed" : "rolling_exposure_split"))
                .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("frameblendoutput", "post/rolling_exposure_split"))
                .withBindGroupLayout(bindings.withUniform("RollingParams", UniformType.UNIFORM_BUFFER).build())
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RG32_FLOAT, ColorTargetState.WRITE_ALL))
                .withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
                .withColorTargetState(2, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .withColorTargetState(3, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));
        if (seed) builder.withShaderDefine("SEED_FROM_RGBA");
        return builder.build();
    }

    static RenderPipeline createPipeline() {
        BindGroupLayout.Builder bindings = BindGroupLayout.builder()
                .withSampler("PreviousSampler").withSampler("CurrentSampler");
        for (int i = 0; i < RollingExposure.MAX_REMOVALS; i++) bindings.withSampler("Removed" + i + "Sampler");
        bindings.withUniform("RollingParams", UniformType.UNIFORM_BUFFER);
        return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("frameblendoutput", "rolling_exposure"))
                .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("frameblendoutput", "post/rolling_exposure"))
                .withBindGroupLayout(bindings.build())
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), RecordingPipelineFormats.BLEND_FORMAT, ColorTargetState.WRITE_ALL))
                .withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .withColorTargetState(2, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .build();
    }

    static boolean render(RenderTarget current, RenderTarget previous, RenderTarget next,
                          RenderTarget output, RenderTarget sourceHistory,
                          RollingExposure<RenderTarget> delta, long now) {
        if (failed) return false;
        try {
            GpuDevice device = RenderSystem.getDevice();
            if (compiledDevice != device) {
                compiledDevice = device;
                java.util.Arrays.fill(compiled, false);
                splitEnabled = device.getDeviceInfo().limits().maxColorAttachments() >= 4
                        && !Boolean.getBoolean("frameblendoutput.rgbaRolling")
                        && device.getDeviceInfo().backendName().toLowerCase(java.util.Locale.ROOT).contains("vulkan");
            }
            boolean split = usesSplitState();
            if (split) ensureSplitTargets(output.width, output.height);
            RenderPipeline pipeline = split ? (seedSplit ? SPLIT_SEED_PIPELINE : SPLIT_PIPELINE) : PIPELINE;
            int variant = split ? (seedSplit ? 2 : 1) : 0;
            if (!compiled[variant]) {
                if (!device.precompilePipeline(pipeline).isValid()) throw new IllegalStateException("Rolling exposure pipeline did not compile");
                compiled[variant] = true;
            }
            // Bind a fresh host-written slice for this submission. Updating a
            // device-local UBO through writeToBuffer adds a GPU copy followed
            // by Minecraft's global memory barrier on every captured frame.
            var encoder = device.createCommandEncoder();
            GpuBufferSlice ubo;
            try (var mapped = encoder.transientMemory().allocateStaging(PARAM_SIZE,
                    device.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM)) {
                Std140Builder b = Std140Builder.intoBuffer(mapped.data());
                for (int i = 0; i < RollingExposure.MAX_REMOVALS; i++) b.putFloat(i < delta.removalCount() ? delta.removedWeight(i) : 0.0f);
                b.putFloat(delta.addedWeight(now));
                b.putFloat(0.0f); b.putFloat(0.0f); b.putFloat(0.0f);
                ubo = mapped.slice();
            }
            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Frameblend Output rolling exposure");
            if (split) descriptor.withColorAttachment(splitRGWrite.getColorTextureView()).withColorAttachment(splitBWrite.getColorTextureView());
            else descriptor.withColorAttachment(next.getColorTextureView());
            descriptor.withColorAttachment(output.getColorTextureView())
                    .withColorAttachment(sourceHistory.getColorTextureView())
                    .withRenderArea(new RenderPass.RenderArea(0, 0, output.width, output.height));
            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            try (RenderPass pass = encoder.createRenderPass(descriptor)) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("RollingParams", ubo);
                if (split && !seedSplit) {
                    pass.bindTexture("PreviousRGSampler", splitRGRead.getColorTextureView(), sampler);
                    pass.bindTexture("PreviousBSampler", splitBRead.getColorTextureView(), sampler);
                } else pass.bindTexture("PreviousSampler", previous.getColorTextureView(), sampler);
                pass.bindTexture("CurrentSampler", current.getColorTextureView(), sampler);
                for (int i = 0; i < RollingExposure.MAX_REMOVALS; i++) {
                    RenderTarget source = i < delta.removalCount() ? delta.removedTarget(i) : current;
                    pass.bindTexture("Removed" + i + "Sampler", source.getColorTextureView(), sampler);
                }
                pass.draw(3, 1, 0, 0);
            }
            if (split) {
                RenderTarget swap = splitRGRead; splitRGRead = splitRGWrite; splitRGWrite = swap;
                swap = splitBRead; splitBRead = splitBWrite; splitBWrite = swap;
                seedSplit = false;
                if (!loggedSplit) {
                    loggedSplit = true;
                    System.out.println("[FBO] Rolling exposure uses split FP32 RGB state (12 bytes/texel, full 32-bit precision per channel).");
                }
            }
            return true;
        } catch (RuntimeException e) {
            if (usesSplitState()) {
                splitDisabled = true;
                releaseSplitTargets();
                System.err.println("[FBO] Split exposure unavailable; continuing with RGBA32 blending: " + e);
                return false;
            }
            failed = true;
            System.err.println("[FBO] Rolling exposure unavailable; continuing with full blending: " + e);
            return false;
        }
    }

    static void invalidate() {
        releaseSplitTargets();
        compiledDevice = null;
        java.util.Arrays.fill(compiled, false);
        splitEnabled = splitDisabled = false;
        seedSplit = true;
        loggedSplit = false;
        failed = false;
    }

    private static void ensureSplitTargets(int width, int height) {
        if (splitRGRead != null && splitRGRead.width == width && splitRGRead.height == height) return;
        releaseSplitTargets();
        splitRGRead = new TextureTarget("Frameblend Output / RG mean A", width, height, false, GpuFormat.RG32_FLOAT);
        splitRGWrite = new TextureTarget("Frameblend Output / RG mean B", width, height, false, GpuFormat.RG32_FLOAT);
        splitBRead = new TextureTarget("Frameblend Output / B mean A", width, height, false, GpuFormat.R32_FLOAT);
        splitBWrite = new TextureTarget("Frameblend Output / B mean B", width, height, false, GpuFormat.R32_FLOAT);
        seedSplit = true;
    }

    private static void releaseSplitTargets() {
        for (RenderTarget target : new RenderTarget[]{splitRGRead, splitRGWrite, splitBRead, splitBWrite}) {
            if (target != null) target.destroyBuffers();
        }
        splitRGRead = splitRGWrite = splitBRead = splitBWrite = null;
    }
}
