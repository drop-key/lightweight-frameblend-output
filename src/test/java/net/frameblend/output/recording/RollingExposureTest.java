package net.frameblend.output.recording;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

public class RollingExposureTest {
    @Test
    public void fullRebasesAreBoundedWithoutFollowingTheRecordingClock() {
        ExposureFrameHistory<Object> history = new ExposureFrameHistory<>();
        RollingExposure<Object> rolling = new RollingExposure<>();
        long now = 1_000_000_000L, exposure = 1_000_000_000L / 60;
        int rebases = 0;
        for (int frame = 0; frame < 1000; frame++) {
            now += 1_000_000L;
            history.capture(now, exposure, Object::new, true);
            if (rolling.prepare(history, now, exposure)) rolling.updated(now);
            else { rolling.rebased(now, exposure); rebases++; }
            history.releaseRetired();
        }
        assertEquals("A one-second sequence needs ten timed rebases, not sixty", 10, rebases);
    }

    @Test
    public void longerRebaseIntervalsKeepBlackWhiteAndNoiseWithinOneOutputLevel() {
        for (int targetHz : new int[]{24, 60, 240}) {
            for (long step : new long[]{100_000L, 526_316L, 1_000_000L}) {
                ExposureFrameHistory<float[]> history = new ExposureFrameHistory<>();
                RollingExposure<float[]> rolling = new RollingExposure<>();
                Random random = new Random(targetHz + step);
                long now = 1_000_000_000L, exposure = 1_000_000_000L / targetHz;
                float mean = 0;
                int longest = 0, consecutive = 0;
                for (int frame = 0; frame < 20_000; frame++) {
                    now += step;
                    var current = history.capture(now, exposure, () -> new float[1], true);
                    int phase = frame % 1800;
                    current.target[0] = (phase < 600 ? 0 : phase < 1200 ? 255 : random.nextInt(256)) / 255.0f;
                    double reference = 0;
                    for (var source : history.frames()) {
                        reference += source.target[0] * (double)source.target[0]
                                * source.overlap(now - exposure, now) / exposure;
                    }
                    if (rolling.prepare(history, now, exposure)) {
                        float change = current.target[0] * current.target[0] * rolling.addedWeight(now);
                        for (int i = 0; i < rolling.removalCount(); i++) {
                            float value = rolling.removedTarget(i)[0];
                            change -= value * value * rolling.removedWeight(i);
                        }
                        mean += change;
                        rolling.updated(now);
                        longest = Math.max(longest, ++consecutive);
                    } else {
                        mean = (float)reference;
                        rolling.rebased(now, exposure);
                        consecutive = 0;
                    }
                    int expected = (int)Math.round(Math.sqrt(Math.max(0, reference)) * 255);
                    int actual = (int)Math.round(Math.sqrt(Math.max(0, mean)) * 255);
                    assertEquals(reference, mean, 0.000005);
                    assertTrue("Output precision changed at " + targetHz + "Hz / " + step + "ns", Math.abs(expected - actual) <= 1);
                    history.releaseRetired();
                }
                assertTrue(longest <= RollingExposure.MAX_INCREMENTAL_UPDATES);
                assertTrue(longest >= 90);
            }
        }
    }

    @Test
    public void rollingAverageMatchesFullExposureThroughFrameTimeChanges() {
        ExposureFrameHistory<float[]> history = new ExposureFrameHistory<>();
        RollingExposure<float[]> rolling = new RollingExposure<>();
        Random random = new Random(4070);
        long now = 1_000_000_000L;
        long exposure = 1_000_000_000L / 60;
        float[] mean = new float[3];
        int incremental = 0;
        for (int i = 0; i < 50_000; i++) {
            now += i % 1000 == 0 ? 40_000_000L : 250_000L + random.nextInt(800_000);
            if (i == 25_000) { exposure = 1_000_000_000L / 144; rolling.reset(); history.reset(); }
            ExposureFrameHistory.Frame<float[]> current = history.capture(now, exposure, () -> new float[3], true);
            for (int c = 0; c < 3; c++) current.target[c] = (i % 300 < 100 ? 0 : i % 300 < 200 ? 255 : random.nextInt(256)) / 255.0f;
            boolean update = rolling.prepare(history, now, exposure);
            double[] reference = new double[3];
            for (ExposureFrameHistory.Frame<float[]> frame : history.frames()) {
                double weight = (double)frame.overlap(now - exposure, now) / exposure;
                for (int c = 0; c < 3; c++) reference[c] += frame.target[c] * (double)frame.target[c] * weight;
            }
            if (update) {
                incremental++;
                for (int c = 0; c < 3; c++) {
                    float change = current.target[c] * current.target[c] * rolling.addedWeight(now);
                    for (int r = 0; r < rolling.removalCount(); r++) {
                        float value = rolling.removedTarget(r)[c];
                        change -= value * value * rolling.removedWeight(r);
                    }
                    mean[c] += change;
                }
                rolling.updated(now);
            } else {
                for (int c = 0; c < 3; c++) mean[c] = (float)reference[c];
                rolling.rebased(now, exposure);
            }
            for (int c = 0; c < 3; c++) {
                assertEquals(reference[c], mean[c], 0.000005);
                int expected = (int)Math.round(Math.sqrt(Math.max(reference[c], 0.0)) * 255.0);
                int actual = (int)Math.round(Math.sqrt(Math.max(mean[c], 0.0)) * 255.0);
                assertTrue("Rolling accumulation changed the 8-bit result", Math.abs(expected - actual) <= 1);
            }
            history.releaseRetired();
        }
        assertTrue("Normal frames should reuse the previous blend", incremental > 45_000);
    }

    @Test
    public void expiredImagesAreNotOverwrittenBeforeSubtraction() {
        ExposureFrameHistory<Object> history = new ExposureFrameHistory<>();
        Object first = history.capture(100L, 10L, Object::new, true).target;
        history.capture(105L, 10L, Object::new, true);
        Object newest = history.capture(110L, 10L, Object::new, true).target;
        assertNotSame(first, newest);
        assertSame(first, history.retiredFrames().iterator().next().target);
        history.releaseRetired();
        assertSame(first, history.capture(115L, 10L, Object::new, true).target);
    }

    @Test
    public void outputPipelineDeclaresAllThreeAttachmentFormats() {
        var formats = RollingExposureRenderer.createPipeline().getColorTargetStates();
        assertEquals(3, formats.length);
        assertEquals(RecordingPipelineFormats.BLEND_FORMAT, formats[0].format());
        assertEquals(com.mojang.blaze3d.GpuFormat.RGBA8_UNORM, formats[1].format());
        assertEquals(com.mojang.blaze3d.GpuFormat.RGBA8_UNORM, formats[2].format());
    }

    @Test
    public void splitPipelinePreservesThreeFloat32Channels() {
        for (boolean seed : new boolean[]{false, true}) {
            var formats = RollingExposureRenderer.createSplitPipeline(seed).getColorTargetStates();
            assertEquals(4, formats.length);
            assertEquals(com.mojang.blaze3d.GpuFormat.RG32_FLOAT, formats[0].format());
            assertEquals(com.mojang.blaze3d.GpuFormat.R32_FLOAT, formats[1].format());
            assertEquals(com.mojang.blaze3d.GpuFormat.RGBA8_UNORM, formats[2].format());
            assertEquals(com.mojang.blaze3d.GpuFormat.RGBA8_UNORM, formats[3].format());
        }
    }
}
