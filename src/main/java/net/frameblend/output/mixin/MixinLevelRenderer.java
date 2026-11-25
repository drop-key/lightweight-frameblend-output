package net.frameblend.output.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.frameblend.output.config.ConfigManager;
import net.frameblend.output.recording.RecordingShaderManager;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelRenderer.class, priority = 800)
public class MixinLevelRenderer {
    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderHead(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
            boolean renderOutline, CameraRenderState cameraState, Matrix4fc modelViewMatrix,
            GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci) {
        if (ConfigManager.getConfig().recordingOverlayEnabled) {
            RecordingShaderManager.captureAllocator(resourceAllocator);
        }
    }
}
