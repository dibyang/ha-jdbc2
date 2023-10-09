package net.sf.hajdbc.lock.reentrant;

import net.sf.hajdbc.lock.ReadLock;

import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ReentrantReadLock extends ReentrantReadWriteLock.ReadLock implements ReadLock {

  private final transient ReentrantReadWriteLock readWriteLock;
  private final String key;
  /**
   * Constructor for use by subclasses
   *
   * @param lock the outer lock object
   * @param key
   * @throws NullPointerException if the lock is null
   */
  protected ReentrantReadLock(ReentrantReadWriteLock lock, String key) {
    super(lock);
    this.readWriteLock = lock;
    this.key = key;
  }

  @Override
  public Object getLockObject() {
    return key;
  }

  @Override
  public boolean isLocked() {
    return readWriteLock.getReadLockCount()>0;
  }

  @Override
  public int getReadCount() {
    return readWriteLock.getReadLockCount();
  }

  public String getKey() {
    return key;
  }
}
