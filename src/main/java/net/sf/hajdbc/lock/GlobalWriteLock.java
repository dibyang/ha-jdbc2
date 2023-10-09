package net.sf.hajdbc.lock;


import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

public class GlobalWriteLock implements WriteLock {
  private ReadLock globalLock;
  private WriteLock lock;

  public GlobalWriteLock(ReadLock globalLock, WriteLock lock) {
    this.globalLock = globalLock;
    this.lock = lock;
  }

  @Override
  public void lock() {
    this.globalLock.lock();
    this.lock.lock();
  }

  @Override
  public void lockInterruptibly() throws InterruptedException {
    this.globalLock.lockInterruptibly();

    try {
      this.lock.lockInterruptibly();
    } catch (InterruptedException e) {
      this.globalLock.unlock();
      throw e;
    }
  }

  @Override
  public boolean tryLock() {
    if (this.globalLock.tryLock()) {
      if (this.lock.tryLock()) {
        return true;
      }
      this.globalLock.unlock();
    }

    return false;
  }

  @Override
  public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
    if (this.globalLock.tryLock(time, unit)) {
      if (this.lock.tryLock(time, unit)) {
        return true;
      }
      this.globalLock.unlock();
    }

    return false;
  }

  @Override
  public void unlock() {
    this.lock.unlock();
    this.globalLock.unlock();
  }

  @Override
  public Condition newCondition() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isLocked() {
    return lock.isLocked();
  }

  @Override
  public Object getLockObject() {
    return lock.getLockObject();
  }

  @Override
  public int getWriteHoldCount() {
    return lock.getWriteHoldCount();
  }
}
