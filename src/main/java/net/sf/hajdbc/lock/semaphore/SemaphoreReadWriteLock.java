package net.sf.hajdbc.lock.semaphore;

import net.sf.hajdbc.lock.WriteLock;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;


public class SemaphoreReadWriteLock implements ReadWriteLock
{
	private final Lock readLock;
	private final Lock writeLock;
	private final String key;
	
	public SemaphoreReadWriteLock(Semaphore semaphore, String key)
	{
		this.key = key;
		this.readLock = new SemaphoreLock(semaphore, key);
		this.writeLock = new SemaphoreWriteLock(semaphore, key);
	}
	
	/**
	 * @see java.util.concurrent.locks.ReadWriteLock#readLock()
	 */
	@Override
	public Lock readLock()
	{
		return this.readLock;
	}

	/**
	 * @see java.util.concurrent.locks.ReadWriteLock#writeLock()
	 */
	@Override
	public Lock writeLock()
	{
		return this.writeLock;
	}
	
	private static class SemaphoreWriteLock implements WriteLock
	{
		private final Semaphore semaphore;
		private final int permits;
		private final AtomicInteger writeCount = new AtomicInteger();

		private final String key;

		SemaphoreWriteLock(Semaphore semaphore, String key)
		{
			this.semaphore = semaphore;
			this.permits = semaphore.availablePermits();
			this.key = key;
		}
		
		/**
		 * Helps avoid write lock starvation, when using an unfair acquisition policy by draining all available permits.
		 * @return the number of drained permits
		 */
		private int drainPermits()
		{
			return this.semaphore.isFair() ? 0 : this.semaphore.drainPermits();
		}
		
		/**
		 * @see java.util.concurrent.locks.Lock#lock()
		 */
		@Override
		public void lock()
		{
			int drained = this.drainPermits();
			
			if (drained < this.permits)
			{
				this.semaphore.acquireUninterruptibly(this.permits - drained);
			}
			writeCount.incrementAndGet();
		}

		/**
		 * @see java.util.concurrent.locks.Lock#lockInterruptibly()
		 */
		@Override
		public void lockInterruptibly() throws InterruptedException
		{
			int drained = this.drainPermits();
			
			if (drained < this.permits)
			{
				try
				{
					this.semaphore.acquire(this.permits - drained);
				}
				catch (InterruptedException e)
				{
					if (drained > 0)
					{
						this.semaphore.release(drained);
					}
					
					throw e;
				}
			}
			writeCount.incrementAndGet();
		}

		/**
		 * @see java.util.concurrent.locks.Lock#tryLock()
		 */
		@Override
		public boolean tryLock()
		{
			// This will barge the fairness queue, so there's no need to drain permits
			boolean acquired = this.semaphore.tryAcquire(this.permits);
			if(acquired){
				writeCount.incrementAndGet();
			}
			return acquired;
		}

		/**
		 * @see java.util.concurrent.locks.Lock#tryLock(long, java.util.concurrent.TimeUnit)
		 */
		@Override
		public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException
		{
			boolean acquired = false;
			int drained = this.drainPermits();
			
			if (drained == this.permits){
				acquired = true;
			}
			if(!acquired) {
				try {
					acquired = this.semaphore.tryAcquire(this.permits - drained, timeout, unit);
				} finally {
					if (!acquired && (drained > 0)) {
						this.semaphore.release(drained);
					}
				}
			}
			if(acquired){
				writeCount.incrementAndGet();
			}
			return acquired;
		}

		/**
		 * @see java.util.concurrent.locks.Lock#unlock()
		 */
		@Override
		public void unlock()
		{
			int expect = this.writeCount.get();
			while(expect>0) {
				if(this.writeCount.compareAndSet(expect,expect-1)) {
					this.semaphore.release(this.permits);
					synchronized (this.key) {
						this.key.notifyAll();
					}
				}else{
					expect = this.writeCount.get();
				}
			}

		}
		
		/**
		 * @see java.util.concurrent.locks.Lock#newCondition()
		 */
		@Override
		public Condition newCondition()
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isLocked() {
			return writeCount.get()>0;
		}

		@Override
		public Object getLockObject() {
			return key;
		}

		@Override
		public String toString() {
			return "{" +
					" key:'" + key + "'" +
					", writeCount:" + writeCount.get() +
					'}';
		}

		@Override
		public int getWriteHoldCount() {
			return writeCount.get();
		}
	}
}
