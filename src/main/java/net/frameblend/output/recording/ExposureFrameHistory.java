package net.frameblend.output.recording;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Reuses frames once they fall outside the exposure, without a fixed sample cap. */
final class ExposureFrameHistory<T> {
    static final class Frame<T> {
        final T target;
        private long start;
        private long end;

        Frame(T target) { this.target = target; }

        long overlap(long exposureStart, long exposureEnd) {
            return Math.max(0L, Math.min(end, exposureEnd) - Math.max(start, exposureStart));
        }
    }

    private final Deque<Frame<T>> active = new ArrayDeque<>();
    private final Deque<Frame<T>> available = new ArrayDeque<>();
    private final Deque<Frame<T>> retired = new ArrayDeque<>();
    private long previousNanos;
    private boolean hasPrevious;

    Frame<T> capture(long now, long exposureNanos, Supplier<T> factory) {
        return capture(now, exposureNanos, factory, false);
    }

    Frame<T> capture(long now, long exposureNanos, Supplier<T> factory, boolean retainExpired) {
        long exposureStart = now - exposureNanos;
        while (!active.isEmpty() && active.peekFirst().end <= exposureStart) {
            (retainExpired ? retired : available).addLast(active.removeFirst());
        }
        Frame<T> frame = available.pollFirst();
        if (frame == null) frame = new Frame<>(factory.get());
        frame.start = hasPrevious && now > previousNanos ? previousNanos : exposureStart;
        frame.end = now;
        previousNanos = now;
        hasPrevious = true;
        active.addLast(frame);
        return frame;
    }

    Iterable<Frame<T>> frames() { return active; }
    Iterable<Frame<T>> retiredFrames() { return retired; }

    void releaseRetired() {
        available.addAll(retired);
        retired.clear();
    }

    void reset() {
        releaseRetired();
        available.addAll(active);
        active.clear();
        hasPrevious = false;
    }

    void clear(Consumer<T> dispose) {
        releaseRetired();
        active.forEach(frame -> dispose.accept(frame.target));
        available.forEach(frame -> dispose.accept(frame.target));
        active.clear();
        available.clear();
        hasPrevious = false;
    }
}
