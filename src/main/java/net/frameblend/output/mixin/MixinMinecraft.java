package net.frameblend.output.mixin;

import net.minecraft.client.Minecraft;
import net.frameblend.output.recording.RecordingShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

// Capture the final client framebuffer near the end of the client frame
@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Unique private boolean fbo$preSubmitHookObserved;

    @Inject(method = "close", at = @At("HEAD"))
    private void fbo$beforeRendererShutdown(CallbackInfo ci) {
        RecordingShaderManager.destroyOnRenderThread();
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/CommandEncoder;submit()V"), require = 0)
    private void fbo$beforeFrameSubmission(boolean advanceGameTime, CallbackInfo ci) {
        if (Boolean.getBoolean("frameblendoutput.tailCapture")) return;
        fbo$preSubmitHookObserved = true;
        RecordingShaderManager.captureFinalFrameAndPresent(true);
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/CommandEncoder;submit()V", shift = At.Shift.AFTER), require = 0)
    private void fbo$afterFrameSubmission(boolean advanceGameTime, CallbackInfo ci) {
        if (fbo$preSubmitHookObserved) RecordingShaderManager.onFrameSubmitted();
    }

    @Inject(method = "runTick", at = @At("TAIL"), require = 0)
    private void fbo$afterRunTick(boolean advanceGameTime, CallbackInfo ci) {
        // A transformed client may replace renderFrame's submission call.
        // Once the primary hook is observed, never capture a frame twice or
        // capture a renderFrame early-return after its resources were reset.
        if (!fbo$preSubmitHookObserved) RecordingShaderManager.captureFinalFrameAndPresent();
    }

    @Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), require = 0)
    private void fbo$beforeReloadResourcePacks(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        RecordingShaderManager.beginResourceReload();
    }

    @Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"), require = 0)
    private void fbo$afterReloadResourcePacks(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        CompletableFuture<Void> reloadFuture = cir.getReturnValue();
        if (reloadFuture != null) {
            reloadFuture.whenComplete((ignored, throwable) -> Minecraft.getInstance().execute(RecordingShaderManager::endResourceReload));
        } else {
            RecordingShaderManager.endResourceReload();
        }
    }
}
