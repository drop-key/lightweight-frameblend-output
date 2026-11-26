package net.frameblend.output.util;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuFence;

/** Close the allocated resources, even when client mods change ring capacities. */
public final class RingBufferCleanup {
    private RingBufferCleanup() {}

    public static void close(GpuBuffer[] buffers, GpuFence[] fences) {
        RuntimeException failure = null;
        for (int i = 0; i < buffers.length; i++) {
            GpuBuffer buffer = buffers[i];
            buffers[i] = null;
            if (buffer == null) continue;
            try { buffer.close(); }
            catch (RuntimeException e) { failure = accumulate(failure, e); }
        }
        for (int i = 0; i < fences.length; i++) {
            GpuFence fence = fences[i];
            fences[i] = null;
            if (fence == null) continue;
            try { fence.close(); }
            catch (RuntimeException e) { failure = accumulate(failure, e); }
        }
        if (failure != null) throw failure;
    }

    private static RuntimeException accumulate(RuntimeException first, RuntimeException next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
}
