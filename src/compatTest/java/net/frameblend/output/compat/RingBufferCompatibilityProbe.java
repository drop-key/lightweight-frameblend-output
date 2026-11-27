package net.frameblend.output.compat;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.MappableRingBuffer;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/** Exercises the actual transformed Minecraft class before graphics startup. */
public final class RingBufferCompatibilityProbe implements PreLaunchEntrypoint {
    private static ProbeBuffer[] buffers;
    private static final boolean[] released = new boolean[2];

    public static void releaseMappings(int owner) {
        if (released[owner]) return;
        released[owner] = true;
        for (ProbeBuffer buffer : buffers) buffer.mappings--;
    }

    @Override
    public void onPreLaunch() {
        try {
            if (!FabricLoader.getInstance().isModLoaded("frameblendoutput")) {
                throw new AssertionError("The production mod must be loaded for this probe");
            }
            for (int[] sizes : new int[][]{{3, 3}, {9, 4}, {4, 9}, {0, 0}}) {
                verify(sizes[0], sizes[1]);
            }
            System.out.println("[FBO TEST] Transformed ring cleanup: both HEAD callbacks run before buffer destruction; mismatched capacities and repeated close passed.");
            // This fixture must never launch the game or require a GPU.
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void verify(int bufferCount, int fenceCount) throws Exception {
        buffers = new ProbeBuffer[bufferCount];
        released[0] = released[1] = false;
        for (int i = 0; i < bufferCount; i++) buffers[i] = new ProbeBuffer();
        ProbeFence[] fences = new ProbeFence[fenceCount];
        for (int i = 0; i < fenceCount; i++) fences[i] = new ProbeFence();

        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        // Skip the constructor's RenderSystem allocation, keeping the real
        // Mixin-transformed close method and Minecraft buffer/fence types.
        MappableRingBuffer ring = (MappableRingBuffer)((Unsafe)singleton.get(null)).allocateInstance(MappableRingBuffer.class);
        set(ring, "buffers", buffers.clone());
        set(ring, "fences", fences.clone());
        ring.close();
        ring.close();
        if (!released[0] || !released[1]) throw new AssertionError("Skipped another mod's HEAD callback");
        for (ProbeBuffer buffer : buffers) {
            if (buffer.mappings != 0 || !buffer.closed) throw new AssertionError("Leaked buffer or mapping");
        }
        for (ProbeFence fence : fences) {
            if (!fence.closed) throw new AssertionError("Leaked fence");
        }
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        Field field = MappableRingBuffer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static final class ProbeBuffer extends GpuBuffer {
        private int mappings = 2;
        private boolean closed;
        private ProbeBuffer() { super(GpuBuffer.USAGE_UNIFORM, 32); }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() {
            if (mappings != 0) throw new IllegalStateException("Attempt to close a mapped buffer");
            if (closed) throw new AssertionError("Double close");
            closed = true;
        }
        @Override public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ProbeFence implements GpuFence {
        private boolean closed;
        @Override public void close() {
            if (closed) throw new AssertionError("Double close");
            closed = true;
        }
        @Override public boolean awaitCompletion(long timeout) {
            throw new AssertionError("Cleanup must not wait for the GPU");
        }
    }
}
