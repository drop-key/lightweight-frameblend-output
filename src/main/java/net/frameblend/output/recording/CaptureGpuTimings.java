package net.frameblend.output.recording;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderSystem;

import java.util.Arrays;
import java.util.Locale;

/** Sparse, nonblocking GPU samples during the existing short startup report. */
final class CaptureGpuTimings {
    private static final int SLOTS = 8;
    private static final boolean[] pending = new boolean[SLOTS];
    private static GpuDevice device;
    private static GpuQueryPool pool;
    private static int frame, active = -1, samples;
    private static double processNanos, copyNanos;
    private static boolean unavailable;

    static void begin() {
        active = -1;
        if (unavailable || ++frame % 32 != 0) return;
        try {
            if (pool == null) {
                device = RenderSystem.getDevice();
                if (!device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan")) {
                    unavailable = true;
                    return;
                }
                pool = device.createTimestampQueryPool(SLOTS * 3);
            }
            collect();
            for (int slot = 0; slot < SLOTS; slot++) {
                if (!pending[slot]) {
                    active = slot;
                    device.createCommandEncoder().writeTimestamp(pool, slot * 3);
                    return;
                }
            }
        } catch (RuntimeException e) { unavailable = true; active = -1; }
    }

    static void beforeSend() { mark(1); }

    static void end() {
        int slot = active;
        if (slot < 0) return;
        mark(2);
        if (active >= 0) pending[slot] = true;
        active = -1;
    }

    private static void mark(int offset) {
        if (active < 0) return;
        try { device.createCommandEncoder().writeTimestamp(pool, active * 3 + offset); }
        catch (RuntimeException e) { unavailable = true; active = -1; }
    }

    private static void collect() {
        if (pool == null) return;
        double period = device.getDeviceInfo().timestampPeriod();
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!pending[slot]) continue;
            var values = pool.getValues(slot * 3, 3);
            if (values[0].isEmpty() || values[1].isEmpty() || values[2].isEmpty()) continue;
            processNanos += (values[1].getAsLong() - values[0].getAsLong()) * period;
            copyNanos += (values[2].getAsLong() - values[1].getAsLong()) * period;
            samples++;
            pending[slot] = false;
        }
    }

    static void report() {
        try {
            if (!unavailable) collect();
            if (samples > 0) System.out.println(String.format(Locale.ROOT,
                    "[FBO] Capture GPU timing: processing %.1f us/frame, shared copy %.1f us/frame (%d asynchronous samples; excludes GL/DX sender work).",
                    processNanos / (samples * 1000.0), copyNanos / (samples * 1000.0), samples));
        } catch (RuntimeException ignored) { }
        reset();
    }

    static void reset() {
        if (pool != null) {
            try { if (RenderSystem.isOnRenderThread()) pool.close(); }
            catch (RuntimeException ignored) { }
        }
        pool = null;
        device = null;
        Arrays.fill(pending, false);
        frame = samples = 0;
        active = -1;
        processNanos = copyNanos = 0;
        unavailable = false;
    }
}
