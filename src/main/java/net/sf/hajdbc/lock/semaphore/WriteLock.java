package net.sf.hajdbc.lock.semaphore;

import net.sf.hajdbc.lock.distributed.LockObject;

import java.util.concurrent.locks.Lock;

public interface WriteLock extends Lock, Locked, LockObject {
}
