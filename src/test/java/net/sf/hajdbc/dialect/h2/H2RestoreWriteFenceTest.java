package net.sf.hajdbc.dialect.h2;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.sf.hajdbc.lock.WriteLock;
import net.sf.hajdbc.lock.semaphore.SemaphoreLockManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 以真实信号量读写锁验证未决激活不会被超时、重启或迟到释放绕过。 */
public class H2RestoreWriteFenceTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static final String SHA = String.join("", java.util.Collections.nCopies(64, "a"));
  private static Set<String> members() { return new HashSet<>(Arrays.asList("source", "target")); }
  private Path create() throws Exception {
    Path file = temporary.getRoot().toPath().resolve("target.intent");
    H2ActivationIntent.create(file, "cluster/target");
    return file;
  }

  @Test public void ownerDepartureAndTimeoutCannotReleasePendingWriteFence() throws Exception {
    Path file = create();
    SemaphoreLockManager locks = new SemaphoreLockManager(false);
    WriteLock raw = (WriteLock) locks.writeLock(null);
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      H2RestoreWriteFence fence = new H2RestoreWriteFence(intent, raw);
      fence.prepare(1, "op", "source", SHA, members());
      assertFalse(fence.requestRelease(1, "op"));
      assertFalse(fence.requestRelease(0, "late-op"));
      assertFalse(locks.readLock(null).tryLock());
      fence.decideAndApply(1, "op", H2ActivationIntent.Decision.ABORT, () -> {
        assertTrue(fence.isHeld());
        assertFalse(locks.readLock(null).tryLock());
      });
      assertFalse(fence.isHeld());
      assertTrue(locks.readLock(null).tryLock());
      locks.readLock(null).unlock();
    }
  }

  @Test public void routeFailureKeepsFenceAndAutomaticRetryAppliesSameCommit() throws Exception {
    Path file = create();
    SemaphoreLockManager locks = new SemaphoreLockManager(false);
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      H2RestoreWriteFence fence = new H2RestoreWriteFence(intent, (WriteLock) locks.writeLock(null));
      fence.prepare(1, "op", "source", SHA, members());
      IOException failure = new IOException("route application failed");
      try {
        fence.decideAndApply(1, "op", H2ActivationIntent.Decision.COMMIT, 1, () -> { throw failure; });
        fail();
      } catch (IOException e) { assertSame(failure, e); }
      assertTrue(fence.isHeld());
      assertFalse(fence.requestRelease(1, "op"));
      assertEquals(H2ActivationIntent.Decision.COMMIT, intent.snapshot().decision);
      AtomicInteger applications = new AtomicInteger();
      fence.decideAndApply(1, "op", H2ActivationIntent.Decision.COMMIT, 1, applications::incrementAndGet);
      fence.decideAndApply(1, "op", H2ActivationIntent.Decision.COMMIT, 1, applications::incrementAndGet);
      assertEquals(1, applications.get());
      assertFalse(fence.isHeld());
      assertTrue(locks.readLock(null).tryLock());
      locks.readLock(null).unlock();
    }
  }

  @Test public void restartRebuildsPendingProtectionBeforeAnyBusinessRead() throws Exception {
    Path file = create();
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      intent.prepare(1, "op", "source", SHA, members());
      intent.commit(1, "op", 1);
    }
    SemaphoreLockManager locks = new SemaphoreLockManager(false);
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      H2RestoreWriteFence fence = new H2RestoreWriteFence(intent, (WriteLock) locks.writeLock(null));
      assertTrue(fence.isHeld());
      assertFalse(locks.readLock(null).tryLock());
      fence.decideAndApply(1, "op", H2ActivationIntent.Decision.COMMIT, 1, () -> assertTrue(fence.isHeld()));
      assertFalse(fence.isHeld());
    }
  }

  @Test public void oldReleaseCannotUnlockNewOperationAndOldCommitCannotApply() throws Exception {
    Path file = create();
    SemaphoreLockManager locks = new SemaphoreLockManager(false);
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      H2RestoreWriteFence fence = new H2RestoreWriteFence(intent, (WriteLock) locks.writeLock(null));
      fence.prepare(1, "old", "source", SHA, members());
      fence.decideAndApply(1, "old", H2ActivationIntent.Decision.ABORT, () -> { });
      fence.prepare(2, "new", "source", SHA, members());
      assertFalse(fence.requestRelease(1, "old"));
      try { fence.decideAndApply(1, "old", H2ActivationIntent.Decision.COMMIT, 1, () -> fail()); fail(); }
      catch (IOException expected) { }
      assertTrue(fence.isHeld());
      assertFalse(locks.readLock(null).tryLock());
      fence.decideAndApply(2, "new", H2ActivationIntent.Decision.ABORT, () -> { });
    }
  }

  @Test public void completedCommitStillRebuildsVolatileRouteOnRestartBeforeUnlock() throws Exception {
    Path file = create();
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      intent.prepare(1, "op", "source", SHA, members());
      intent.commit(1, "op", 1);
      intent.applied(1, "op");
    }
    SemaphoreLockManager locks = new SemaphoreLockManager(false);
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      H2RestoreWriteFence fence = new H2RestoreWriteFence(intent, (WriteLock) locks.writeLock(null));
      assertTrue(fence.isHeld());
      assertFalse(fence.requestRelease(1, "op"));
      assertFalse(locks.readLock(null).tryLock());
      AtomicInteger routes = new AtomicInteger();
      fence.decideAndApply(1, "op", H2ActivationIntent.Decision.COMMIT, 1, routes::incrementAndGet);
      fence.decideAndApply(1, "op", H2ActivationIntent.Decision.COMMIT, 1, routes::incrementAndGet);
      assertEquals(1, routes.get());
      assertFalse(fence.isHeld());
    }
  }

  @Test(timeout = 30000) public void abruptJvmExitPreservesBothPendingAndCommittedWriteProtection() throws Exception {
    for (String phase : Arrays.asList("PENDING", "COMMIT")) {
      Path file = temporary.getRoot().toPath().resolve(phase + ".intent");
      H2ActivationIntent.create(file, "cluster/target");
      String executable = java.nio.file.Paths.get(System.getProperty("java.home"), "bin",
          System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
      String classpath = new java.io.File(CrashWithIntent.class.getProtectionDomain().getCodeSource()
          .getLocation().toURI()).getPath() + java.io.File.pathSeparator
          + new java.io.File(H2ActivationIntent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
      Process child = new ProcessBuilder(executable, "-cp", classpath, CrashWithIntent.class.getName(), file.toString(), phase)
          .redirectErrorStream(true).redirectOutput(temporary.newFile(phase + ".log")).start();
      try {
        assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(24, child.exitValue());
        SemaphoreLockManager locks = new SemaphoreLockManager(false);
        try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
          assertEquals(H2ActivationIntent.Decision.valueOf(phase), intent.snapshot().decision);
          H2RestoreWriteFence fence = new H2RestoreWriteFence(intent, (WriteLock) locks.writeLock(null));
          assertFalse(locks.readLock(null).tryLock());
          assertFalse(fence.requestRelease(1, "op"));
          H2ActivationIntent.Decision decision = phase.equals("PENDING")
              ? H2ActivationIntent.Decision.ABORT : H2ActivationIntent.Decision.COMMIT;
          fence.decideAndApply(1, "op", decision, () -> assertTrue(fence.isHeld()));
          assertTrue(locks.readLock(null).tryLock());
          locks.readLock(null).unlock();
        }
      } finally {
        if (child.isAlive()) child.destroyForcibly();
        assertTrue(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
      }
    }
  }

  /** 进程退出不经过 finally，验证文件锁释放及未决决定保留。 */
  public static class CrashWithIntent {
    public static void main(String[] args) throws Exception {
      H2ActivationIntent intent = new H2ActivationIntent(java.nio.file.Paths.get(args[0]), "cluster/target");
      String digest = String.join("", java.util.Collections.nCopies(64, "a"));
      intent.prepare(1, "op", "source", digest,
          new java.util.HashSet<>(java.util.Arrays.asList("source", "target")));
      if (args[1].equals("COMMIT")) intent.commit(1, "op", 1);
      Runtime.getRuntime().halt(24);
    }
  }
}
