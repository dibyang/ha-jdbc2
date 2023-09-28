package net.sf.hajdbc.lock.distributed;

import net.sf.hajdbc.lock.semaphore.ShareLock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;

public class NodeShareLock implements ShareLock {
  private final ShareLock lock;
  private final AtomicInteger ownerShared = new AtomicInteger();

  public NodeShareLock(ShareLock lock) {
    this.lock = lock;
  }

  @Override
  public synchronized void lock() {
    this.lock.lock();
    this.ownerShared.incrementAndGet();
  }

  @Override
  public synchronized void lockInterruptibly() throws InterruptedException {
    this.lock.lockInterruptibly();
    this.ownerShared.incrementAndGet();
  }

  @Override
  public synchronized boolean tryLock() {
    boolean b = this.lock.tryLock();
    if(b){
      this.ownerShared.incrementAndGet();
    }
    return b;
  }

  @Override
  public synchronized boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
    boolean b = this.lock.tryLock(time, unit);
    if(b){
      this.ownerShared.incrementAndGet();
    }
    return b;
  }

  @Override
  public synchronized void unlock() {
    if (this.lock.isLocked() && this.ownerShared.get() > 0) {
      this.lock.unlock();
      this.ownerShared.decrementAndGet();
    }
  }

  @Override
  public Condition newCondition() {
    return this.lock.newCondition();
  }

  @Override
  public boolean isLocked() {
    return this.getShared()>0;
  }

  @Override
  public int getShared() {
    return ownerShared.get();
  }

  @Override
  public Object getLockObject() {
    return this.lock.getLockObject();
  }

}
