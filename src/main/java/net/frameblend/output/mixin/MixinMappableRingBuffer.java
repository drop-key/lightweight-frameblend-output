package net.frameblend.output.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuFence;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.frameblend.output.util.RingBufferCleanup;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MappableRingBuffer.class)
public abstract class MixinMappableRingBuffer {
    @Shadow @Final private GpuBuffer[] buffers;
    @Shadow @Final private GpuFence[] fences;

    @Inject(method = "close", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/MappableRingBuffer;buffers:[Lcom/mojang/blaze3d/buffers/GpuBuffer;",
            ordinal = 0), cancellable = true)
    private void fbo$closeAllocatedSlots(CallbackInfo ci) {
        // Run after HEAD callbacks: Polydium releases its persistent mappings
        // there, and Vulkan rejects closing a buffer that is still mapped.
        // Lunar/other mods may resize these arrays independently or rewrite the
        // original fixed loop bound. Their actual lengths are authoritative.
        RingBufferCleanup.close(buffers, fences);
        ci.cancel();
    }
}
