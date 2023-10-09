package net.sf.hajdbc.lock.distributed;

import net.sf.hajdbc.distributed.CommandDispatcher;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.lock.WriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

class DistributedLock implements Lock {
  static final Logger LOG = LoggerFactory.getLogger(DistributedLock.class);
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

  private String getKey(){
    StringBuilder key = new StringBuilder();
    if(lock instanceof WriteLock){
      key.append("write");
    }else{
      key.append("read");
    }
    if(lock instanceof LockObject){
      key.append("("+((LockObject)lock).getLockObject()+")");
    }else{
      key.append("()");
    }
    return key.toString();
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
    String key = getKey();


    if (masterLock.tryLock()) {
      try {
        if (this.lock.tryLock(time, unit)) {
          //LOG.debug("Lock local ok. key:{}", key);
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
    LOG.debug("tryLock end {} key:{} locked:{}", descriptor.getMember().toString(), key, locked);

    return locked;
  }


  private boolean lockMembers() {
    boolean locked = true;
    LOG.debug("lockMembers begin {}", descriptor.getMember().toString());
    Map<Member, Boolean> results = this.dispatcher.executeAll(new MemberAcquireLockCommand(this.descriptor), descriptor.getMember());
    LOG.debug("lockMembers results:{}", results);
    for (Map.Entry<Member, Boolean> entry : results.entrySet()) {
      locked &= entry.getValue();
    }
    LOG.debug("lockMembers end  {} locked:{}", descriptor.getMember().toString(), locked);

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
