package net.frameblend.output.recording;

import org.junit.Test;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class CaptureTimingsTest {
    @Test
    public void timingReportUsesThePrintlnHookHandledByMinecraftLogging() throws Exception {
        PrintStream original = System.out;
        AtomicReference<String> line = new AtomicReference<>();
        try {
            System.setOut(new PrintStream(OutputStream.nullOutputStream()) {
                @Override public void println(String value) { line.set(value); }
            });
            CaptureTimings.reset();
            long now = System.nanoTime();
            for (String name : new String[]{"started", "sampledAt"}) {
                var field = CaptureTimings.class.getDeclaredField(name);
                field.setAccessible(true);
                field.setLong(null, now - 8_000_000_000L);
            }
            CaptureTimings.record(now - 100_000L, now - 80_000L, true);
            assertNotNull("Logging streams can ignore printf; use their println hook", line.get());
            assertTrue(line.get().contains("[FBO] Capture CPU timing:"));
            assertTrue(line.get().contains("Spout"));
        } finally {
            System.setOut(original);
            CaptureTimings.reset();
        }
    }
}
