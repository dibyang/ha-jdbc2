/*
 * HA-JDBC: High-Availability JDBC
 * Copyright (C) 2012  Paul Ferraro
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.hajdbc.lock.semaphore;

import net.sf.hajdbc.lock.ReadLock;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * An implementation of {@link java.util.concurrent.locks.Lock} using a binary semaphore.
 * Unlike the {@link java.util.concurrent.locks.ReentrantLock} this lock can be locked and unlocked by different threads.
 * Conditions are not supported.
 * 
 * @author Paul Ferraro
 * @deprecated
 * @see net.sf.hajdbc.lock.reentrant.ReentrantLockManager
 */
@Deprecated
public class SemaphoreLock implements Lock, ReadLock
{
	private transient final Semaphore semaphore;
	private final AtomicInteger shared = new AtomicInteger();

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
		this.shared.incrementAndGet();
	}

	/**
	 * @see java.util.concurrent.locks.Lock#lockInterruptibly()
	 */
	@Override
	public void lockInterruptibly() throws InterruptedException
	{
		this.semaphore.acquire();
		this.shared.incrementAndGet();
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
			this.shared.incrementAndGet();
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
			this.shared.incrementAndGet();
		}
		return locked;
	}

	/**
	 * @see java.util.concurrent.locks.Lock#unlock()
	 */
	@Override
	public void unlock()
	{
		this.semaphore.release();
		this.shared.decrementAndGet();
	}

	@Override
	public int getReadCount() {
		return this.shared.get();
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
				", shared:" + shared +
				'}';
	}
}
