package net.sf.hajdbc.lock.semaphore;

import net.sf.hajdbc.lock.ReadLock;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;


public class SemaphoreLock implements Lock, ReadLock
{
	private final Semaphore semaphore;
	private final AtomicInteger readCount = new AtomicInteger();

	private final String key;
	
	public SemaphoreLock(Semaphore semaphore, String key)
	{
		this.semaphore = semaphore;
		this.key = key;
	}
	
	/**
	 * @see java.util.concurrent.locks.Lock#lock()
	 */
	@Override
	public void lock()
	{
		this.semaphore.acquireUninterruptibly();
		this.readCount.incrementAndGet();
	}

	/**
	 * @see java.util.concurrent.locks.Lock#lockInterruptibly()
	 */
	@Override
	public void lockInterruptibly() throws InterruptedException
	{
		this.semaphore.acquire();
		this.readCount.incrementAndGet();
	}

	/**
	 * @see java.util.concurrent.locks.Lock#newCondition()
	 */
	@Override
	public Condition newCondition()
	{
		throw new UnsupportedOperationException();
	}

	/**
	 * @see java.util.concurrent.locks.Lock#tryLock()
	 */
	@Override
	public boolean tryLock()
	{
		boolean locked = this.semaphore.tryAcquire();
		if(locked){
			this.readCount.incrementAndGet();
		}
		return locked;
	}

	/**
	 * @see java.util.concurrent.locks.Lock#tryLock(long, java.util.concurrent.TimeUnit)
	 */
	@Override
	public boolean tryLock(long time, TimeUnit unit) throws InterruptedException
	{
		boolean locked = this.semaphore.tryAcquire(time, unit);
		if(locked){
			this.readCount.incrementAndGet();
		}
		return locked;
	}

	/**
	 * @see java.util.concurrent.locks.Lock#unlock()
	 */
	@Override
	public void unlock()
	{
		int expect = this.readCount.get();
		while(expect>0) {
			if(this.readCount.compareAndSet(expect,expect-1)) {
				this.semaphore.release();
				synchronized (this.key) {
					this.key.notifyAll();
				}
			}else{
				expect = this.readCount.get();
			}
		}
	}

	@Override
	public int getReadCount() {
		return this.readCount.get();
	}

	@Override
	public boolean isLocked() {
		return this.getReadCount()>0;
	}

	@Override
	public Object getLockObject() {
		return key;
	}

	@Override
	public String toString() {
		return "{" +
				" key:'" + key + "'" +
				", readCount:" + readCount +
				'}';
	}
}
