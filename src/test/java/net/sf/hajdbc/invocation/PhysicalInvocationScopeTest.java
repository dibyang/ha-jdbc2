package net.sf.hajdbc.invocation;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import net.sf.hajdbc.util.concurrent.SynchronousExecutor;
import static org.junit.Assert.*;

/** 用门闩与可控执行器验证物理退出，不依赖 sleep 或真实数据库。 */
public class PhysicalInvocationScopeTest {
    @Test public void nestedScopesRestoreContext() {
        assertFalse(PhysicalInvocationScope.isActive());
        try (PhysicalInvocationScope outer = PhysicalInvocationScope.open()) {
            try (PhysicalInvocationScope inner = PhysicalInvocationScope.open()) {
                assertTrue(PhysicalInvocationScope.isActive());
            }
            assertTrue(PhysicalInvocationScope.isActive());
        }
        assertFalse(PhysicalInvocationScope.isActive());
    }

    @Test public void wrongCloseOrderDoesNotRemoveScope() {
        PhysicalInvocationScope outer = PhysicalInvocationScope.open();
        PhysicalInvocationScope inner = PhysicalInvocationScope.open();
        try { outer.close(); fail(); }
        catch (IllegalStateException expected) { assertTrue(PhysicalInvocationScope.isActive()); }
        inner.close();
        outer.close();
        assertFalse(PhysicalInvocationScope.isActive());
    }

    @Test public void normalAndFailedResultsKeepFutureContract() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            List<Future<Integer>> result = PhysicalInvocationScope.invokeAll(executor, Arrays.asList(
                    () -> { assertTrue(PhysicalInvocationScope.isActive()); return 7; },
                    () -> { throw new IllegalArgumentException("failure"); }));
            assertEquals(Integer.valueOf(7), result.get(0).get());
            try { result.get(1).get(); fail(); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof IllegalArgumentException); }
            assertFalse(executor.submit(PhysicalInvocationScope::isActive).get());
        } finally { shutdown(executor); }
    }

    @Test public void rejectionAbandonsUnstartedCallableEvenIfExecutorLaterRunsIt() throws Exception {
        AtomicReference<Callable<?>> queued = new AtomicReference<>();
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutorService executor = new AbstractExecutorService() {
            public void shutdown() { }
            public List<Runnable> shutdownNow() { return Collections.emptyList(); }
            public boolean isShutdown() { return false; }
            public boolean isTerminated() { return false; }
            public boolean awaitTermination(long n, TimeUnit unit) { return true; }
            public void execute(Runnable command) { throw new AssertionError(); }
            @Override public <T> List<Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> tasks) {
                queued.set(tasks.iterator().next());
                throw new RejectedExecutionException();
            }
        };
        try {
            PhysicalInvocationScope.invokeAll(executor, Collections.singletonList(() -> invoked.getAndSet(true)));
            fail();
        } catch (RejectedExecutionException expected) { }
        queued.get().call();
        assertFalse(invoked.get());
    }

    @Test public void interruptionRetainsPhysicalOccupancyAndDiscardsQueuedWork() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch childInterrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean returned = new AtomicBoolean();
        AtomicBoolean queuedRan = new AtomicBoolean();
        AtomicBoolean restoredInterrupt = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                PhysicalInvocationScope.invokeAll(executor, Arrays.asList(() -> {
                    entered.countDown();
                    boolean waiting = true;
                    while (waiting) {
                        try { release.await(); waiting = false; }
                        catch (InterruptedException expected) { childInterrupted.countDown(); }
                    }
                    return null;
                }, () -> { queuedRan.set(true); return null; }));
                failure.set(new AssertionError("Expected interruption"));
            } catch (InterruptedException expected) {
                restoredInterrupt.set(Thread.currentThread().isInterrupted());
            } catch (Throwable e) { failure.set(e); }
            finally { returned.set(true); }
        });
        try {
            caller.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            assertTrue(childInterrupted.await(2, TimeUnit.SECONDS));
            assertFalse(returned.get());
        } finally {
            release.countDown();
            caller.join(2000);
            shutdown(executor);
        }
        assertFalse(caller.isAlive());
        assertNull(failure.get());
        assertTrue(returned.get());
        assertTrue(restoredInterrupt.get());
        assertFalse(queuedRan.get());
    }

    @Test public void partialRejectionWaitsForStartedTaskInBothExecutors() throws Exception {
        for (boolean synchronous : new boolean[] { false, true }) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch rejected = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger submissions = new AtomicInteger();
            AtomicBoolean returned = new AtomicBoolean();
            AtomicBoolean exited = new AtomicBoolean();
            AtomicBoolean queuedRan = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            RejectedExecutionException rejection = new RejectedExecutionException("partial submission");
            ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<Runnable>()) {
                @Override public void execute(Runnable command) {
                    if (submissions.incrementAndGet() == 2) {
                        try { assertTrue(entered.await(2, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                        rejected.countDown();
                        throw rejection;
                    }
                    super.execute(command);
                }
            };
            ExecutorService executor = synchronous ? new SynchronousExecutor(pool) : pool;
            Callable<Void> blocking = () -> {
                entered.countDown();
                boolean done = false;
                while (!done) {
                    try { release.await(); done = true; }
                    catch (InterruptedException ignored) { }
                }
                exited.set(true);
                return null;
            };
            Callable<Void> pending = () -> { queuedRan.set(true); return null; };
            List<Callable<Void>> tasks = synchronous
                    ? Arrays.asList(() -> null, blocking, pending, pending)
                    : Arrays.asList(blocking, pending);
            Thread caller = new Thread(() -> {
                try { PhysicalInvocationScope.invokeAll(executor, tasks); }
                catch (Throwable e) { failure.set(e); }
                finally { returned.set(true); }
            });
            try {
                caller.start();
                assertTrue(rejected.await(2, TimeUnit.SECONDS));
                assertFalse(returned.get());
                assertFalse(exited.get());
            } finally {
                release.countDown();
                caller.join(2000);
                shutdown(pool);
            }
            assertFalse(caller.isAlive());
            assertTrue(exited.get());
            assertSame(rejection, failure.get());
            assertFalse(queuedRan.get());
        }
    }

    @Test public void nestedScopeDefersInterruptUntilOuterClose() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        Future<?> blocker = executor.submit(() -> {
            try { release.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            try (PhysicalInvocationScope outer = PhysicalInvocationScope.open()) {
                try (PhysicalInvocationScope inner = PhysicalInvocationScope.open()) {
                    Thread.currentThread().interrupt();
                    try {
                        PhysicalInvocationScope.invokeAll(executor, Collections.singletonList(() -> 1));
                        fail("Expected interruption");
                    } catch (InterruptedException expected) { }
                    assertFalse(Thread.currentThread().isInterrupted());
                }
                assertFalse(Thread.currentThread().isInterrupted());
                release.countDown();
                blocker.get(2, TimeUnit.SECONDS);
                assertEquals(Integer.valueOf(2), PhysicalInvocationScope.invokeAll(executor,
                        Collections.singletonList(() -> 2)).get(0).get());
            }
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            release.countDown();
            shutdown(executor);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }
}
