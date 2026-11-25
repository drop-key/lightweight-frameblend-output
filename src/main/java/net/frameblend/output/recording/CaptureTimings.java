package net.frameblend.output.recording;

import java.util.Locale;

/** One short CPU timing report per enable; no GPU waits are introduced for profiling. */
final class CaptureTimings {
    private static long started, sampledAt, processNanos, sendNanos;
    private static int frames, rollingFrames;
    private static boolean reported;

    static long begin() { return reported ? 0L : System.nanoTime(); }

    static void record(long begin, long beforeSend, boolean rolling) {
        if (reported) return;
        long end = System.nanoTime();
        if (started == 0L) started = begin;
        if (end - started < 2_000_000_000L) return;
        if (sampledAt == 0L) sampledAt = begin;
        frames++;
        if (rolling) rollingFrames++;
        processNanos += beforeSend - begin;
        sendNanos += end - beforeSend;
        if (end - sampledAt < 5_000_000_000L) return;
        System.out.println(String.format(Locale.ROOT,
                "[FBO] Capture CPU timing: %.0f FPS, processing %.1f us/frame, Spout %.1f us/frame, rolling %d/%d frames. GPU execution is asynchronous.",
                frames * 1_000_000_000.0 / (end - sampledAt), processNanos / (frames * 1000.0),
                sendNanos / (frames * 1000.0), rollingFrames, frames));
        reported = true;
        CaptureGpuTimings.report();
        SpoutFrameSender.reportTimings();
    }

    static void reset() {
        CaptureGpuTimings.reset();
        started = sampledAt = processNanos = sendNanos = 0L;
        frames = rollingFrames = 0;
        reported = false;
    }
}
