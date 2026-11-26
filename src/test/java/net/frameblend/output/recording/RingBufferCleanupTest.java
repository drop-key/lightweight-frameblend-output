package net.frameblend.output.recording;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import net.frameblend.output.util.RingBufferCleanup;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RingBufferCleanupTest {
    @Test
    public void differentArrayLengthsCloseEveryAllocatedResourceOnce() {
        for (int[] lengths : new int[][]{{9, 4}, {4, 9}, {4, 4}, {3, 3}}) {
            AtomicInteger closed = new AtomicInteger();
            GpuBuffer[] buffers = new GpuBuffer[lengths[0]];
            GpuFence[] fences = new GpuFence[lengths[1]];
            for (int i = 0; i < buffers.length; i++) buffers[i] = buffer(closed, null);
            for (int i = 0; i < fences.length; i++) fences[i] = fence(closed);
            RingBufferCleanup.close(buffers, fences);
            RingBufferCleanup.close(buffers, fences);
            assertEquals(lengths[0] + lengths[1], closed.get());
        }
    }

    @Test
    public void failedBufferDoesNotPreventOtherResourcesFromClosing() {
        AtomicInteger closed = new AtomicInteger();
        RuntimeException expected = new RuntimeException("close failed");
        GpuBuffer[] buffers = {buffer(closed, expected), null, buffer(closed, null)};
        GpuFence[] fences = {fence(closed)};
        try {
            RingBufferCleanup.close(buffers, fences);
            fail("The original failure must remain visible");
        } catch (RuntimeException actual) { assertSame(expected, actual); }
        assertEquals(3, closed.get());
        RingBufferCleanup.close(buffers, fences);
        assertEquals(3, closed.get());
    }

    private static GpuBuffer buffer(AtomicInteger closed, RuntimeException failure) {
        return new GpuBuffer(GpuBuffer.USAGE_UNIFORM, 32) {
            private boolean isClosed;
            @Override public boolean isClosed() { return isClosed; }
            @Override public void close() {
                assertFalse("Double close", isClosed);
                isClosed = true;
                closed.incrementAndGet();
                if (failure != null) throw failure;
            }
            @Override public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static GpuFence fence(AtomicInteger closed) {
        return new GpuFence() {
            @Override public void close() { closed.incrementAndGet(); }
            @Override public boolean awaitCompletion(long timeout) { throw new AssertionError("Cleanup must not wait"); }
        };
    }
}
