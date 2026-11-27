package net.frameblend.output.compat.mixin;

import net.frameblend.output.compat.RingBufferCompatibilityProbe;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MappableRingBuffer.class, priority = 1200)
public abstract class EarlyMappingCleanupMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void releaseMapping(CallbackInfo ci) {
        RingBufferCompatibilityProbe.releaseMappings(0);
    }
}
