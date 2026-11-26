package net.frameblend.output.recording;

/** CPU plan for an exact sliding exposure; large changes use a full recalculation. */
final class RollingExposure<T> {
    static final int MAX_REMOVALS = 4;
    static final int MAX_INCREMENTAL_UPDATES = 128;
    static final long MAX_REBASE_INTERVAL = 100_000_000L;
    private final Object[] targets = new Object[MAX_REMOVALS];
    private final float[] weights = new float[MAX_REMOVALS];
    private int count;
    private long previousNanos;
    private long exposureNanos;
    private long rebaseNanos;
    private boolean valid;
    private int updatesSinceRebase;

    boolean prepare(ExposureFrameHistory<T> history, long now, long exposure) {
        count = 0;
        if (!valid || exposure != exposureNanos || now <= previousNanos
                || now - previousNanos >= exposure || now - rebaseNanos >= MAX_REBASE_INTERVAL
                || updatesSinceRebase >= MAX_INCREMENTAL_UPDATES) return false;
        long oldStart = previousNanos - exposure;
        long newStart = now - exposure;
        if (!collect(history.retiredFrames(), oldStart, newStart, exposure)) return false;
        return collect(history.frames(), oldStart, newStart, exposure);
    }

    private boolean collect(Iterable<ExposureFrameHistory.Frame<T>> frames,
                            long start, long end, long exposure) {
        for (ExposureFrameHistory.Frame<T> frame : frames) {
            long removed = frame.overlap(start, end);
            if (removed <= 0L) continue;
            if (count == MAX_REMOVALS) return false;
            targets[count] = frame.target;
            weights[count++] = (float)((double)removed / exposure);
        }
        return true;
    }

    float addedWeight(long now) { return (float)((double)(now - previousNanos) / exposureNanos); }
    int removalCount() { return count; }
    float removedWeight(int index) { return weights[index]; }
    @SuppressWarnings("unchecked") T removedTarget(int index) { return (T) targets[index]; }

    void updated(long now) { previousNanos = now; updatesSinceRebase++; }
    void rebased(long now, long exposure) {
        valid = true;
        previousNanos = rebaseNanos = now;
        exposureNanos = exposure;
        updatesSinceRebase = 0;
    }
    void reset() {
        valid = false;
        updatesSinceRebase = 0;
        java.util.Arrays.fill(targets, null);
        count = 0;
    }
}
