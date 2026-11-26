package net.frameblend.output.recording;

import org.junit.Test;
import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RecordingTimingTest {
    @Test
    public void fullExposureSurvivesHighFPSAndTexturesAreReused() {
        ExposureFrameHistory<Object> history = new ExposureFrameHistory<>();
        AtomicInteger allocated = new AtomicInteger();
        long exposure = 1_000_000_000L / 60;
        long now = 1_000_000_000L;
        int samples = 0;
        for (int i = 0; i < 10_000; i++, now += 500_000L) {
            history.capture(now, exposure, () -> { allocated.incrementAndGet(); return new Object(); });
            long total = 0;
            samples = 0;
            HashSet<Object> targets = new HashSet<>();
            for (ExposureFrameHistory.Frame<Object> frame : history.frames()) {
                total += frame.overlap(now - exposure, now);
                assertTrue("An active source texture was overwritten", targets.add(frame.target));
                samples++;
            }
            assertEquals("The shutter window was truncated", exposure, total);
        }
        assertEquals(34, samples);
        assertTrue("Texture count grows with running time", allocated.get() <= 35);
        history.reset();
        assertFalse(history.frames().iterator().hasNext());
        history.capture(now, exposure, () -> { throw new AssertionError("Reset lost reusable textures"); });
        AtomicInteger disposed = new AtomicInteger();
        history.clear(ignored -> disposed.incrementAndGet());
        assertEquals(allocated.get(), disposed.get());
    }

    @Test
    public void variableFrameTimesKeepAllOverlappingSamples() {
        ExposureFrameHistory<Object> history = new ExposureFrameHistory<>();
        Random random = new Random(4070);
        long now = 1_000_000_000L;
        for (int i = 0; i < 10_000; i++) {
            now += 150_000L + random.nextInt(3_000_000);
            long exposure = 1_000_000_000L / (i < 5000 ? 60 : 144);
            history.capture(now, exposure, Object::new);
            long total = 0;
            for (ExposureFrameHistory.Frame<Object> frame : history.frames()) {
                long overlap = frame.overlap(now - exposure, now);
                assertTrue(overlap > 0L);
                total += overlap;
            }
            assertEquals(exposure, total);
        }
    }
}
