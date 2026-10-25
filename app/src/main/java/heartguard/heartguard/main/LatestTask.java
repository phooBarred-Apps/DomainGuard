package heartguard.heartguard.main;

import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Coalesces short input bursts and prevents replaced work or callbacks from being applied. */
final class LatestTask<T> {
    interface Read<T> { T get(); }
    interface Apply<T> { void accept(T value); }
    private final ScheduledExecutorService worker;
    private final Executor delivery;
    private Future<?> pending;
    private long generation;

    LatestTask(ScheduledExecutorService worker, Executor delivery) {
        this.worker = worker;
        this.delivery = delivery;
    }

    synchronized void submit(Read<T> read, Apply<T> apply) {
        cancel();
        long requested = generation;
        pending = worker.schedule(() -> {
            final T result;
            try { result = read.get(); }
            catch (RuntimeException failure) {
                // A scheduled Future otherwise silently retains a failed read that nobody observes.
                delivery.execute(() -> { throw failure; });
                return;
            }
            delivery.execute(() -> {
                synchronized (LatestTask.this) {
                    if (requested == generation) apply.accept(result);
                }
            });
        }, 100, TimeUnit.MILLISECONDS);
    }

    synchronized void cancel() {
        generation++;
        // Let an already-running database read close its cursor normally.
        if (pending != null) pending.cancel(false);
        pending = null;
    }
}
