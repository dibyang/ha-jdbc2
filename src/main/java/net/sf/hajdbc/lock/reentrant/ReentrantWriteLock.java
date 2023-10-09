package net.sf.hajdbc.lock.reentrant;

import net.sf.hajdbc.lock.WriteLock;

import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ReentrantWriteLock extends ReentrantReadWriteLock.WriteLock implements WriteLock {

  private final transient ReentrantReadWriteLock readWriteLock;
  private final String key;
  /**
   * Constructor for use by subclasses
   *
   * @param lock the outer lock object
   * @param key
   * @throws NullPointerException if the lock is null
   */
  protected ReentrantWriteLock(ReentrantReadWriteLock lock, String key) {
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
    return readWriteLock.isWriteLocked();
  }

  public String getKey() {
    return key;
  }

  @Override
  public int getWriteHoldCount() {
    return readWriteLock.getWriteHoldCount();
  }
}
