package net.frameblend.output.recording;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** A bounded, nonblocking handoff; the graphics context belongs to the worker. */
final class AsyncFramePublisher<T> implements AutoCloseable {
    private final ArrayBlockingQueue<T> queue;
    private final Thread thread;
    private volatile boolean accepting = true;
    private volatile Throwable failure;

    AsyncFramePublisher(int capacity, Runnable initialize, Consumer<T> publish, Runnable release) {
        this(capacity, initialize, publish, () -> false, release);
    }

    AsyncFramePublisher(int capacity, Runnable initialize, Consumer<T> publish, BooleanSupplier idle, Runnable release) {
        queue = new ArrayBlockingQueue<>(capacity);
        thread = new Thread(() -> {
            try {
                initialize.run();
                while (accepting || !queue.isEmpty()) {
                    boolean pendingWork = idle.getAsBoolean();
                    T frame;
                    try { frame = pendingWork ? queue.poll(200L, TimeUnit.MICROSECONDS) : queue.take(); }
                    catch (InterruptedException ignored) { continue; }
                    if (frame != null) publish.accept(frame);
                }
            } catch (Throwable e) {
                failure = e;
                accepting = false;
            } finally {
                try { release.run(); }
                catch (Throwable e) { if (failure == null) failure = e; }
            }
        }, "fbo-spout-publisher");
        thread.setDaemon(true);
        thread.start();
    }

    boolean offer(T frame) {
        if (failure != null) throw new IllegalStateException("Spout publisher failed", failure);
        return accepting && queue.offer(frame);
    }

    void checkFailure() {
        if (failure != null) throw new IllegalStateException("Spout publisher failed", failure);
    }

    boolean isAccepting() { return accepting; }

    void requestStop() {
        accepting = false;
        thread.interrupt();
    }

    @Override
    public void close() {
        requestStop();
        if (thread == Thread.currentThread()) return;
        try { thread.join(2000L); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping Spout publisher", e);
        }
        if (thread.isAlive()) throw new IllegalStateException("Spout publisher has not finished using shared textures");
    }
}
