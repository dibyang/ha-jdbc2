package net.sf.hajdbc.lock.distributed;

import net.sf.hajdbc.lock.ReadLock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;

public class NodeReadLock implements ReadLock {
  private final ReadLock lock;
  private final AtomicInteger ownerReadCount = new AtomicInteger();

  public NodeReadLock(ReadLock lock) {
    this.lock = lock;
  }

  @Override
  public void lock() {
    this.lock.lock();
    this.ownerReadCount.incrementAndGet();
  }

  @Override
  public void lockInterruptibly() throws InterruptedException {
    this.lock.lockInterruptibly();
    this.ownerReadCount.incrementAndGet();
  }

  @Override
  public boolean tryLock() {
    boolean b = this.lock.tryLock();
    if(b){
      this.ownerReadCount.incrementAndGet();
    }
    return b;
  }

  @Override
  public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
    boolean b = this.lock.tryLock(time, unit);
    if(b){
      this.ownerReadCount.incrementAndGet();
    }
    return b;
  }

  @Override
  public void unlock() {
    int expect = this.ownerReadCount.get();
    while(expect>0) {
      if (this.ownerReadCount.compareAndSet(expect, expect-1)) {
        this.lock.unlock();
      }else{
        expect = this.ownerReadCount.get();
      }
    }
  }

  @Override
  public Condition newCondition() {
    return this.lock.newCondition();
  }

  @Override
  public boolean isLocked() {
    return this.getReadCount()>0;
  }

  @Override
  public int getReadCount() {
    return ownerReadCount.get();
  }

  @Override
  public Object getLockObject() {
    return this.lock.getLockObject();
  }

}
