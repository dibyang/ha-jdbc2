package net.sf.hajdbc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * {@link Tracer.TraceFileState} 的单调时钟和并发刷新测试。
 */
public class TracerTest {
  @Test
  public void stableReadsCheckFileOncePerThreeSecondWindow() {
    FakeClock clock = new FakeClock();
    CountingFileStatus file = new CountingFileStatus(false);
    RecordingListener listener = new RecordingListener();
    Tracer.TraceFileState state = new Tracer.TraceFileState(file, clock, listener);

    assertFalse(state.isTrace());
    for (int i = 0; i < 100000; ++i) {
      assertFalse(state.isTrace());
    }
    assertEquals(1, file.count.get());
    assertTrue(listener.values.isEmpty());

    clock.nanos.set(Tracer.TraceFileState.TTL_NANOS - 1L);
    assertFalse(state.isTrace());
    assertEquals(1, file.count.get());

    clock.nanos.set(Tracer.TraceFileState.TTL_NANOS);
    assertFalse(state.isTrace());
    assertEquals(2, file.count.get());
    assertTrue(listener.values.isEmpty());
  }

  @Test
  public void changesAreVisibleAtExpiryAndLoggedOnce() {
    FakeClock clock = new FakeClock();
    CountingFileStatus file = new CountingFileStatus(false);
    RecordingListener listener = new RecordingListener();
    Tracer.TraceFileState state = new Tracer.TraceFileState(file, clock, listener);

    assertFalse(state.isTrace());
    file.exists = true;
    clock.nanos.set(Tracer.TraceFileState.TTL_NANOS);
    assertTrue(state.isTrace());
    assertEquals(Collections.singletonList(Boolean.TRUE), listener.values);

    file.exists = false;
    clock.nanos.addAndGet(Tracer.TraceFileState.TTL_NANOS);
    assertFalse(state.isTrace());
    assertEquals(2, listener.values.size());
    assertEquals(Boolean.FALSE, listener.values.get(1));
  }

  @Test
  public void concurrentExpiryPerformsOneFileCheck() throws Exception {
    final int threads = 32;
    FakeClock clock = new FakeClock();
    final CountingFileStatus file = new CountingFileStatus(false);
    Tracer.TraceFileState state = new Tracer.TraceFileState(file, clock, new RecordingListener());
    assertFalse(state.isTrace());

    clock.nanos.set(Tracer.TraceFileState.TTL_NANOS);
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> futures = new ArrayList<Future<Boolean>>(threads);
    try {
      for (int i = 0; i < threads; ++i) {
        futures.add(executor.submit(() -> {
          ready.countDown();
          assertTrue(start.await(5, TimeUnit.SECONDS));
          return state.isTrace();
        }));
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      for (Future<Boolean> future: futures) {
        assertFalse(future.get(5, TimeUnit.SECONDS));
      }
      assertEquals(2, file.count.get());
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void failedRefreshDoesNotAdvanceTtl() {
    FakeClock clock = new FakeClock();
    CountingFileStatus file = new CountingFileStatus(false);
    Tracer.TraceFileState state = new Tracer.TraceFileState(file, clock, new RecordingListener());
    assertFalse(state.isTrace());

    clock.nanos.set(Tracer.TraceFileState.TTL_NANOS);
    file.failure = new SecurityException("denied");
    assertSecurityFailure(state);
    assertEquals(2, file.count.get());

    file.failure = null;
    assertFalse(state.isTrace());
    assertEquals(3, file.count.get());
  }

  private static void assertSecurityFailure(Tracer.TraceFileState state) {
    try {
      state.isTrace();
      fail("Expected SecurityException");
    } catch (SecurityException e) {
      assertEquals("denied", e.getMessage());
    }
  }

  private static final class FakeClock implements Tracer.NanoClock {
    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long nanoTime() {
      return this.nanos.get();
    }
  }

  private static final class CountingFileStatus implements Tracer.FileStatus {
    private final AtomicInteger count = new AtomicInteger();
    private volatile boolean exists;
    private volatile SecurityException failure;

    private CountingFileStatus(boolean exists) {
      this.exists = exists;
    }

    @Override
    public boolean exists() {
      this.count.incrementAndGet();
      if (this.failure != null) {
        throw this.failure;
      }
      return this.exists;
    }
  }

  private static final class RecordingListener implements Tracer.StateChangeListener {
    private final List<Boolean> values = Collections.synchronizedList(new ArrayList<Boolean>());

    @Override
    public void changed(boolean enabled) {
      this.values.add(enabled);
    }
  }
}
