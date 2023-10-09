package net.sf.hajdbc.lock.distributed;

import net.sf.hajdbc.lock.WriteLock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;

public class NodeWriteLock implements WriteLock {
  private final WriteLock lock;
  private final AtomicInteger ownerWriteCount = new AtomicInteger();

  public NodeWriteLock(WriteLock lock) {
    this.lock = lock;
  }

  @Override
  public synchronized void lock() {
    this.lock.lock();
    this.ownerWriteCount.incrementAndGet();
  }

  @Override
  public synchronized void lockInterruptibly() throws InterruptedException {
    this.lock.lockInterruptibly();
    this.ownerWriteCount.incrementAndGet();
  }

  @Override
  public synchronized boolean tryLock() {
    boolean b = this.lock.tryLock();
    if (b) {
      this.ownerWriteCount.incrementAndGet();
    }
    return b;
  }

  @Override
  public synchronized boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
    boolean b = this.lock.tryLock(time, unit);
    if (b) {
      this.ownerWriteCount.incrementAndGet();
    }
    return b;
  }

  @Override
  public synchronized void unlock() {
    if(isLocked()) {
      this.lock.unlock();
      this.ownerWriteCount.decrementAndGet();
    }
  }

  @Override
  public Condition newCondition() {
    return this.lock.newCondition();
  }

  @Override
  public boolean isLocked() {
    return this.ownerWriteCount.get()>0;
  }

  @Override
  public Object getLockObject() {
    return this.lock.getLockObject();
  }

  @Override
  public int getWriteHoldCount() {
    return this.lock.getWriteHoldCount();
  }
}
