package net.sf.hajdbc.lock.distributed;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.dialect.h2.H2ActivationIntent;
import net.sf.hajdbc.dialect.h2.H2RestoreWriteFence;
import net.sf.hajdbc.distributed.CommandDispatcher;
import net.sf.hajdbc.distributed.CommandDispatcherFactory;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.lock.semaphore.SemaphoreLockManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 真实成员移除和旧命令入口必须服从持久恢复保护，不能直接 unlock。 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class RestoreMemberReleaseTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static final String SHA = String.join("", java.util.Collections.nCopies(64, "a"));
  private static Set<String> members() { return new HashSet<>(Arrays.asList("source", "target")); }

  @Test public void memberRemovalAndOldReleaseKeepPreparedLockUntilDecisionApplied() throws Exception {
    DatabaseCluster cluster = mock(DatabaseCluster.class);
    SemaphoreLockManager local = new SemaphoreLockManager(false);
    when(cluster.getId()).thenReturn("cluster");
    when(cluster.getLockManager()).thenReturn(local);
    CommandDispatcherFactory factory = mock(CommandDispatcherFactory.class);
    CommandDispatcher dispatcher = mock(CommandDispatcher.class);
    doReturn(dispatcher).when(factory).createCommandDispatcher(eq("cluster.lock"), any(), any(), any());
    DistributedLockManager manager = new DistributedLockManager(cluster, factory);
    Member owner = mock(Member.class);
    RestoreLockDescriptor first = new RestoreLockDescriptor(owner, "target", 1, "first");
    Path file = temporary.getRoot().toPath().resolve("target.intent");
    H2ActivationIntent.create(file, "cluster/target");
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      H2RestoreWriteFence fence = manager.prepareRestoreLock(first, intent, "source", SHA, members());
      assertFalse(local.readLock(null).tryLock());
      manager.removed(owner);
      assertTrue(fence.isHeld());
      new MemberReleaseLockCommand(first).execute(manager);
      assertTrue(fence.isHeld());
      assertFalse(local.readLock(null).tryLock());
      fence.decideAndApply(1, "first", H2ActivationIntent.Decision.ABORT, () -> { });
      assertFalse(fence.isHeld());
      assertFalse(new MemberAcquireLockCommand(first).execute(manager));

      RestoreLockDescriptor second = new RestoreLockDescriptor(owner, "target", 2, "second");
      H2RestoreWriteFence next = manager.prepareRestoreLock(second, intent, "source", SHA, members());
      new MemberReleaseLockCommand(first).execute(manager);
      assertFalse(new MemberAcquireLockCommand(first).execute(manager));
      assertTrue(next.isHeld());
      assertFalse(local.readLock(null).tryLock());
      next.decideAndApply(2, "second", H2ActivationIntent.Decision.COMMIT, 1, () -> { });
      assertFalse(next.isHeld());
      assertTrue(local.readLock(null).tryLock());
      local.readLock(null).unlock();
    }
  }

  @Test public void ordinaryMemberReleaseStillUsesExistingLockPath() {
    RemoteLockDescriptor ordinary = mock(RemoteLockDescriptor.class);
    LockCommandContext context = mock(LockCommandContext.class);
    java.util.concurrent.locks.Lock lock = mock(java.util.concurrent.locks.Lock.class);
    when(context.getLock(ordinary)).thenReturn(lock);
    new MemberReleaseLockCommand(ordinary).execute(context);
    verify(lock).unlock();
    verify(context).getLock(ordinary);
  }

  @Test public void ordinaryOwnerDepartureStillReleasesItsWriteLock() throws Exception {
    DatabaseCluster cluster = mock(DatabaseCluster.class);
    SemaphoreLockManager local = new SemaphoreLockManager(false);
    when(cluster.getId()).thenReturn("cluster");
    when(cluster.getLockManager()).thenReturn(local);
    CommandDispatcherFactory factory = mock(CommandDispatcherFactory.class);
    doReturn(mock(CommandDispatcher.class)).when(factory)
        .createCommandDispatcher(eq("cluster.lock"), any(), any(), any());
    DistributedLockManager manager = new DistributedLockManager(cluster, factory);
    Member owner = mock(Member.class);
    RemoteLockDescriptor descriptor = mock(RemoteLockDescriptor.class);
    when(descriptor.getMember()).thenReturn(owner);
    when(descriptor.getType()).thenReturn(LockType.WRITE);
    java.util.concurrent.locks.Lock write = manager.getLock(descriptor);
    assertTrue(write.tryLock());
    assertFalse(local.readLock(null).tryLock());
    manager.removed(owner);
    assertTrue(local.readLock(null).tryLock());
    local.readLock(null).unlock();
    assertTrue(manager.getAllLocks(true).isEmpty());
  }
}
