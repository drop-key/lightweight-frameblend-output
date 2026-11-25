package net.frameblend.output.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.frameblend.output.recording.RecordingPipelineFormats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PostChain.class)
public class MixinPostChain {
    @ModifyExpressionValue(
            method = "createPass",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline;builder([Lcom/mojang/blaze3d/pipeline/RenderPipeline$Snippet;)Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;")
    )
    private static RenderPipeline.Builder frameblendOutput$configureRecordingFormat(
            RenderPipeline.Builder builder, TextureManager textureManager,
            PostChainConfig.Pass pass, Identifier name) {
        return RecordingPipelineFormats.configure(builder, pass.fragmentShaderId());
    }
}
