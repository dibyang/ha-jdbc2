package net.sf.hajdbc.lock;

import net.sf.hajdbc.lock.distributed.LockObject;

import java.util.concurrent.locks.Lock;

public interface ReadLock extends Lock, Locked, LockObject {
  int getReadCount();
}
