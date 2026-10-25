package heartguard.heartguard.main;

import org.junit.After;
import org.junit.Test;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class LatestTaskTest {
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
    private final LatestTask<Integer> task = new LatestTask<>(worker, deliveries::add);

    @After public void close() { task.cancel(); worker.shutdownNow(); }

    // Test: A burst of 30 queued requests executes only the last database read.
    // Why: Ignoring obsolete results alone still wastes the database work behind every keystroke.
    @Test public void replacesQueuedReads() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        worker.execute(() -> await(release));
        AtomicInteger reads = new AtomicInteger(), result = new AtomicInteger();
        for (int i = 1; i <= 30; i++) {
            final int value = i;
            task.submit(() -> { reads.incrementAndGet(); return value; }, result::set);
        }
        release.countDown();
        deliver();
        assertEquals(1, reads.get());
        assertEquals(30, result.get());
    }

    // Test: Replacing an already-running read suppresses its callback and delivers the latest result.
    // Why: Database reads can finish after the query changes; old results must never replace new ones.
    @Test public void ignoresReplacedRunningRead() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        task.submit(() -> { started.countDown(); await(release); return 1; }, result::set);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        task.submit(() -> 2, result::set);
        release.countDown();
        deliver();
        assertEquals(0, result.get());
        deliver();
        assertEquals(2, result.get());
    }

    // Test: Recycling a row cancels a completed but undelivered result; the task can serve its next app.
    // Why: Reused RecyclerView holders must not display another app's domains after scrolling.
    @Test public void cancelInvalidatesDeliveryAndAllowsReuse() throws Exception {
        AtomicInteger result = new AtomicInteger();
        task.submit(() -> 1, result::set);
        Runnable old = deliveries.poll(5, TimeUnit.SECONDS);
        assertNotNull(old);
        task.cancel();
        old.run();
        assertEquals(0, result.get());
        task.submit(() -> 2, result::set);
        deliver();
        assertEquals(2, result.get());
    }

    private void deliver() throws Exception {
        Runnable callback = deliveries.poll(5, TimeUnit.SECONDS);
        assertNotNull("Read should produce a callback", callback);
        callback.run();
    }

    // Test: Failed reads reach the delivery thread instead of disappearing inside an unobserved Future.
    // Why: A database failure must remain diagnosable after replacing AsyncTask with queued reads.
    @Test public void reportsReadFailure() throws Exception {
        IllegalStateException failure = new IllegalStateException("read failed");
        task.submit(() -> { throw failure; }, value -> fail("A failed read has no value"));
        try { deliver(); fail("Expected read failure"); }
        catch (IllegalStateException actual) { assertSame(failure, actual); }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
