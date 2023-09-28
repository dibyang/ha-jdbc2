package net.sf.hajdbc.lock.distributed;

import net.sf.hajdbc.lock.semaphore.WriteLock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

public class NodeWriteLock implements WriteLock {
  private final WriteLock lock;
  private volatile boolean ownerLocked;

  public NodeWriteLock(WriteLock lock) {
    this.lock = lock;
  }

  @Override
  public synchronized void lock() {
    if(!this.ownerLocked) {
      this.lock.lock();
      this.ownerLocked = true;
    }
  }

  @Override
  public synchronized void lockInterruptibly() throws InterruptedException {
    if(!this.ownerLocked) {
      this.lock.lockInterruptibly();
      this.ownerLocked = true;
    }
  }

  @Override
  public synchronized boolean tryLock() {
    if(!this.ownerLocked) {
      boolean b = this.lock.tryLock();
      if (b) {
        this.ownerLocked = true;
      }
      return b;
    }
    return this.ownerLocked;
  }

  @Override
  public synchronized boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
    if(!this.ownerLocked) {
      boolean b = this.lock.tryLock(time, unit);
      if (b) {
        this.ownerLocked = true;
      }
      return b;
    }
    return this.ownerLocked;
  }

  @Override
  public synchronized void unlock() {
    if(this.ownerLocked) {
      this.lock.unlock();
      this.ownerLocked = false;
    }
  }

  @Override
  public Condition newCondition() {
    return this.lock.newCondition();
  }

  @Override
  public boolean isLocked() {
    return ownerLocked;
  }

  @Override
  public Object getLockObject() {
    return this.lock.getLockObject();
  }

}
