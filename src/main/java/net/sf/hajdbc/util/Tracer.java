package net.sf.hajdbc.util;

import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * 通过约定文件动态控制低频诊断日志，并用短 TTL 避免高频路径反复访问文件系统。
 */
public enum Tracer {
  invoke,
  observe,
  db_state;
  private final Logger logger = LoggerFactory.getLogger(this.getClass());
  private final File traceFile;
  private final TraceFileState traceState;

  Tracer() {
    this.traceFile = HaJdbcPaths.traceFile(this.name()).toFile();
    this.traceState = new TraceFileState(new FileStatus() {
      @Override
      public boolean exists() {
        return Tracer.this.traceFile.exists();
      }
    }, new NanoClock() {
      @Override
      public long nanoTime() {
        return System.nanoTime();
      }
    }, new StateChangeListener() {
      @Override
      public void changed(boolean enabled) {
        Tracer.this.logger.log(Level.INFO, "trace {0}={1}", Tracer.this.name(), enabled);
      }
    });
  }

  /**
   * 返回当前 trace 开关状态；稳定状态仅进行一次 volatile 内存读取。
   */
  public boolean isTrace() {
    return this.traceState.isTrace();
  }

  interface FileStatus {
    boolean exists();
  }

  interface NanoClock {
    long nanoTime();
  }

  interface StateChangeListener {
    void changed(boolean enabled);
  }

  static final class TraceFileState {
    static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(3);

    private final FileStatus fileStatus;
    private final NanoClock clock;
    private final StateChangeListener listener;
    private volatile Snapshot snapshot = Snapshot.uninitialized();

    TraceFileState(FileStatus fileStatus, NanoClock clock, StateChangeListener listener) {
      this.fileStatus = fileStatus;
      this.clock = clock;
      this.listener = listener;
    }

    boolean isTrace() {
      long now = this.clock.nanoTime();
      Snapshot current = this.snapshot;
      if (current.isValid(now)) {
        return current.enabled;
      }

      boolean changed = false;
      boolean enabled;
      synchronized (this) {
        now = this.clock.nanoTime();
        current = this.snapshot;
        if (current.isValid(now)) {
          return current.enabled;
        }

        enabled = this.fileStatus.exists();
        changed = current.initialized && (current.enabled != enabled);
        this.snapshot = new Snapshot(true, enabled, now);
      }

      // 日志可能阻塞，不能让它扩大 TTL 刷新的同步临界区。
      if (changed) {
        this.listener.changed(enabled);
      }
      return enabled;
    }
  }

  private static final class Snapshot {
    private final boolean initialized;
    private final boolean enabled;
    private final long checkedAtNanos;

    private Snapshot(boolean initialized, boolean enabled, long checkedAtNanos) {
      this.initialized = initialized;
      this.enabled = enabled;
      this.checkedAtNanos = checkedAtNanos;
    }

    private static Snapshot uninitialized() {
      return new Snapshot(false, false, 0L);
    }

    private boolean isValid(long now) {
      return this.initialized && ((now - this.checkedAtNanos) < TraceFileState.TTL_NANOS);
    }
  }
}
