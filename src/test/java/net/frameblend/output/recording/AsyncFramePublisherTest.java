package net.frameblend.output.recording;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class AsyncFramePublisherTest {
    @Test
    public void pendingGpuWorkIsPolledWithoutAnotherProducedFrame() throws Exception {
        AtomicBoolean pending = new AtomicBoolean();
        CountDownLatch completed = new CountDownLatch(1);
        try (AsyncFramePublisher<Integer> publisher = new AsyncFramePublisher<>(1, () -> {}, frame -> pending.set(true), () -> {
            if (pending.compareAndSet(true, false)) completed.countDown();
            return pending.get();
        }, () -> {})) {
            assertTrue(publisher.offer(1));
            assertTrue("GPU completion must be checked even when no more frames arrive", completed.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    public void blockedSenderDoesNotBlockProducerAndQueueStaysBounded() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<Integer> sent = new ArrayList<>();
        AsyncFramePublisher<Integer> publisher = new AsyncFramePublisher<>(2, () -> {}, frame -> {
            entered.countDown();
            boolean waiting = true;
            while (waiting) {
                try { release.await(); waiting = false; }
                catch (InterruptedException ignored) { }
            }
            sent.add(frame);
        }, () -> {});
        try {
            assertTrue(publisher.offer(0));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(publisher.offer(1));
            assertTrue(publisher.offer(2));
            assertFalse("A full queue must return instead of waiting for the sender", publisher.offer(3));
        } finally {
            release.countDown();
            publisher.close();
        }
        assertEquals(List.of(0, 1, 2), sent);
    }

    @Test
    public void contextSetupSendAndCleanupHaveOneOwner() {
        AtomicReference<Thread> owner = new AtomicReference<>();
        List<Integer> frames = new ArrayList<>();
        AtomicReference<Thread> cleanup = new AtomicReference<>();
        try (AsyncFramePublisher<Integer> publisher = new AsyncFramePublisher<>(4,
                () -> owner.set(Thread.currentThread()), frame -> {
                    assertSame(owner.get(), Thread.currentThread());
                    frames.add(frame);
                }, () -> cleanup.set(Thread.currentThread()))) {
            for (int i = 0; i < 4; i++) assertTrue(publisher.offer(i));
        }
        assertEquals(List.of(0, 1, 2, 3), frames);
        assertSame(owner.get(), cleanup.get());
        assertNotSame(Thread.currentThread(), owner.get());
    }

    @Test
    public void workerFailureIsReportedToTheProducer() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        RuntimeException cause = new RuntimeException("sender failed");
        try (AsyncFramePublisher<Integer> publisher = new AsyncFramePublisher<>(1,
                () -> { throw cause; }, frame -> {}, ended::countDown)) {
            assertTrue(ended.await(1, TimeUnit.SECONDS));
            try {
                publisher.checkFailure();
                fail("Failure was hidden");
            } catch (IllegalStateException e) { assertSame(cause, e.getCause()); }
        }
    }
}
