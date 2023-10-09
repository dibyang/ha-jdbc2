package net.sf.hajdbc.lock.distributed;

import net.sf.hajdbc.distributed.CommandDispatcher;
import net.sf.hajdbc.distributed.Member;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

class DistributedLock implements Lock {
  public static final int DEFAULT_LOCK_TIMEOUT = 3;
  private final RemoteLockDescriptor descriptor;
  private final Lock lock;
  /**
   * 获取分布式读写锁的分布式互斥锁，必须先获取分布式互斥锁才能获取分布式读写锁。
   * 无论是否获取读写锁成功或失败，结束后都必须要释放该互斥锁
   */
  private final Lock masterLock;
  private final CommandDispatcher<LockCommandContext> dispatcher;

  DistributedLock(RemoteLockDescriptor descriptor, Lock lock, Lock masterLock, CommandDispatcher<LockCommandContext> dispatcher) {
    this.descriptor = descriptor;
    this.lock = lock;
    this.masterLock = masterLock;
    this.dispatcher = dispatcher;
  }

  @Override
  public void lock() {
    boolean locked = false;
    while (!locked) {
      locked = tryLock();
      if (!locked) {
        if (lock instanceof LockObject) {
          Object lockObject = ((LockObject) lock).getLockObject();

          synchronized (lockObject) {
            try {
              lockObject.wait();
            } catch (InterruptedException e) {
              //e.printStackTrace();
              Thread.yield();
            }
          }
        }
        //Thread.yield();
      }
    }
  }

  @Override
  public void lockInterruptibly() throws InterruptedException {
    boolean locked = false;

    while (!locked) {
      locked = tryLock();

      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException();
      }

      if (!locked) {
        if (lock instanceof LockObject) {
          Object lockObject = ((LockObject) lock).getLockObject();

          synchronized (lockObject) {
            lockObject.wait();
          }
        }
        Thread.yield();
      }
    }
  }

  @Override
  public boolean tryLock() {
    boolean locked = false;

    try {
      locked = this.tryLock(DEFAULT_LOCK_TIMEOUT, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      //ignore e.printStackTrace();
      // LOG.warn(null,e);
    }

    return locked;
  }

  /**
   *
   * @param time
   * @param unit
   * @return
   * @throws InterruptedException
   */
  @Override
  public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
    boolean locked = false;

//    获取分布式读写锁的分布式互斥锁，必须先获取分布式互斥锁才能获取分布式读写锁。
//    无论是否获取读写锁成功或失败，结束后都必须要释放该互斥锁
    DistributedLockManager.LOG.info("tryLock begin {}",  descriptor.getMember().toString());

    if (masterLock.tryLock()) {
      DistributedLockManager.LOG.info("tryLock local. timeout:{}", unit.toMillis(time));
      try {
        if (this.lock.tryLock(time, unit)) {
          DistributedLockManager.LOG.info("Lock local ok.");
          try {
            locked = this.lockMembers();
          } finally {
            if (!locked) {
              this.lock.unlock();
            }
          }
        }
      } finally {
        this.masterLock.unlock();
      }
    }
    DistributedLockManager.LOG.info("tryLock end {} locked:{}", descriptor.getMember().toString(), locked);

    return locked;
  }

  private boolean lockMembers() {
    boolean locked = true;
    DistributedLockManager.LOG.info("lockMembers begin {}", descriptor.getMember().toString());
    Map<Member, Boolean> results = this.dispatcher.executeAll(new MemberAcquireLockCommand(this.descriptor), descriptor.getMember());
    DistributedLockManager.LOG.info("lockMembers results:{}", results);
    for (Map.Entry<Member, Boolean> entry : results.entrySet()) {
      locked &= entry.getValue();
    }
    DistributedLockManager.LOG.info("lockMembers end  {} locked:{}", descriptor.getMember().toString(), locked);

    if (!locked) {
      this.unlockMembers();
    }

    return locked;
  }

  @Override
  public void unlock() {
    this.lock.unlock();
    this.unlockMembers();
  }

  private void unlockMembers() {
    this.dispatcher.executeAll(new MemberReleaseLockCommand(this.descriptor), descriptor.getMember());
  }

  @Override
  public Condition newCondition() {
    throw new UnsupportedOperationException();
  }
}
