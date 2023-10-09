package net.sf.hajdbc.lock.reentrant;

import net.sf.hajdbc.lock.GlobalReadLock;
import net.sf.hajdbc.lock.GlobalWriteLock;
import net.sf.hajdbc.lock.LockManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ReentrantLockManager implements LockManager {

  static final String EMPTY = "";
  private final ConcurrentMap<String, ReentrantReadWriteLock> lockMap = new ConcurrentHashMap<>();

  private final boolean fair;

  public ReentrantLockManager(boolean fair)
  {
    this.fair = fair;
  }

  @Override
  public void start() throws Exception {

  }

  @Override
  public void stop() {

  }

  @Override
  public Lock readLock(String object)
  {
    object = (object!=null)?object:EMPTY;
    ReentrantReadWriteLock globalRWLock = this.getReadWriteLock(EMPTY);
    ReentrantReadLock globalReadLock = new ReentrantReadLock(globalRWLock, EMPTY);

    if(EMPTY.equals(object)){
      return globalReadLock;
    }else{
      return new GlobalReadLock(globalReadLock, new ReentrantReadLock(this.getReadWriteLock(object), object));
    }
  }


  @Override
  public Lock writeLock(String object)
  {
    object = (object!=null)?object:EMPTY;
    ReentrantReadWriteLock globalRWLock = this.getReadWriteLock(EMPTY);
    if(EMPTY.equals(object)){
      return new ReentrantWriteLock(globalRWLock, object);
    }else{
      ReentrantReadLock globalReadLock = new ReentrantReadLock(globalRWLock, EMPTY);
      ReentrantWriteLock writeLock = new ReentrantWriteLock(this.getReadWriteLock(object), object);
      return new GlobalWriteLock(globalReadLock, writeLock);
    }
  }

  private synchronized ReentrantReadWriteLock getReadWriteLock(String object)
  {
    // CHM cannot use a null key
    String key = (object != null) ? object : EMPTY;

    ReentrantReadWriteLock lock = this.lockMap.get(key);

    if (lock == null)
    {
      lock = new ReentrantReadWriteLock(this.fair);

      ReentrantReadWriteLock existing = this.lockMap.putIfAbsent(key, lock);

      if (existing != null)
      {
        lock = existing;
      }
    }

    return lock;
  }

  @Override
  public Lock onlyLock(String id) {
    throw new UnsupportedOperationException();
  }
}
