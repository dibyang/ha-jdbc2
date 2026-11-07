package net.sf.hajdbc.lock.distributed;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import net.sf.hajdbc.distributed.CommandDispatcher;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.lock.WriteLock;
import net.sf.hajdbc.lock.semaphore.SemaphoreLockManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * 恢复协议前置验证：复现现有锁的时序边界，不代表这些行为安全。
 * 使用真实分布式锁、成员命令和本地信号量锁，仅控制消息交付顺序。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class RestoreLockOrderingTest {
  @Test public void delayedAcquireCanArriveAfterFailedAttemptHasReleasedMembers() throws Exception {
    Member owner = mock(Member.class);
    Member peer = mock(Member.class);
    RemoteLockDescriptor descriptor = mock(RemoteLockDescriptor.class);
    when(descriptor.getMember()).thenReturn(owner);
    when(descriptor.getType()).thenReturn(LockType.WRITE);
    SemaphoreLockManager localManager = new SemaphoreLockManager(false);
    SemaphoreLockManager remoteManager = new SemaphoreLockManager(false);
    NodeWriteLock local = new NodeWriteLock((WriteLock) localManager.writeLock(null));
    NodeWriteLock remote = new NodeWriteLock((WriteLock) remoteManager.writeLock(null));
    LockCommandContext context = mock(LockCommandContext.class);
    when(context.getLock(descriptor)).thenReturn(remote);
    CommandDispatcher<LockCommandContext> dispatcher = mock(CommandDispatcher.class);
    AtomicReference<MemberAcquireLockCommand> delayed = new AtomicReference<>();
    when(dispatcher.executeAll(isA(MemberAcquireLockCommand.class), eq(owner))).thenAnswer(call -> {
      delayed.set((MemberAcquireLockCommand) call.getArguments()[0]);
      return Collections.singletonMap(peer, null);
    });
    when(dispatcher.executeAll(isA(MemberReleaseLockCommand.class), eq(owner))).thenAnswer(call -> {
      ((MemberReleaseLockCommand) call.getArguments()[0]).execute(context);
      return Collections.singletonMap(peer, null);
    });
    DistributedLock lock = new DistributedLock(descriptor, local, new ReentrantLock(), dispatcher);
    try {
      assertFalse(lock.tryLock(0, TimeUnit.MILLISECONDS));
      assertFalse(local.isLocked());
      assertFalse(remote.isLocked());
      // 调用者已经结束失败尝试，但原请求仍能在远端新取得写锁。
      assertTrue(delayed.get().execute(context));
      assertTrue(remote.isLocked());
    } finally {
      remote.unlock();
      local.unlock();
    }
    assertFalse(remote.isLocked());
    assertFalse(local.isLocked());
  }

  @Test public void missingResponseSetIsNotAnAllMemberAcknowledgement() throws Exception {
    RemoteLockDescriptor descriptor = mock(RemoteLockDescriptor.class);
    Member owner = mock(Member.class);
    when(descriptor.getMember()).thenReturn(owner);
    CommandDispatcher<LockCommandContext> dispatcher = mock(CommandDispatcher.class);
    when(dispatcher.executeAll(any(MemberAcquireLockCommand.class), eq(owner)))
        .thenReturn(Collections.emptyMap());
    NodeWriteLock local = new NodeWriteLock((WriteLock) new SemaphoreLockManager(false).writeLock(null));
    DistributedLock lock = new DistributedLock(descriptor, local, new ReentrantLock(), dispatcher);
    boolean acquired = false;
    try {
      acquired = lock.tryLock(0, TimeUnit.MILLISECONDS);
      // 当前实现没有目标成员集合校验；恢复协议不能据此宣称全部成员已持锁。
      assertTrue(acquired);
    } finally {
      if (acquired) lock.unlock();
    }
    assertFalse(local.isLocked());
  }
}
