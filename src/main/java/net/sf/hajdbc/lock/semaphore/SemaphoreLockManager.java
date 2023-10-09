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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

import net.sf.hajdbc.lock.*;

/**
 * @author Paul Ferraro
 * @deprecated
 * @see net.sf.hajdbc.lock.reentrant.ReentrantLockManager
 */
@Deprecated
public class SemaphoreLockManager implements LockManager
{
	public static final String EMPTY = "";
	private final ConcurrentMap<String, ReadWriteLock> lockMap = new ConcurrentHashMap<String, ReadWriteLock>();

	private final boolean fair;
	
	public SemaphoreLockManager(boolean fair)
	{
		this.fair = fair;
	}
	
	/**
	 * @see net.sf.hajdbc.lock.LockManager#readLock(java.lang.String)
	 */
	@Override
	public Lock readLock(String object)
	{
		object = (object!=null)?object:EMPTY;
		Lock lock = this.getReadWriteLock(EMPTY).readLock();
		
		return EMPTY.equals(object) ? lock : new GlobalReadLock((ReadLock)lock, (ReadLock)this.getReadWriteLock(object).readLock());
	}
	
	/**
	 * @see net.sf.hajdbc.lock.LockManager#writeLock(java.lang.String)
	 */
	@Override
	public Lock writeLock(String object)
	{
		object = (object!=null)?object:EMPTY;
		ReadWriteLock readWriteLock = this.getReadWriteLock(EMPTY);
		
		return EMPTY.equals(object) ? readWriteLock.writeLock() : new GlobalWriteLock((ReadLock) readWriteLock.readLock(), (WriteLock) this.getReadWriteLock(object).writeLock());
	}

	@Override
	public Lock onlyLock(String id) {
		throw new UnsupportedOperationException();
	}


	private synchronized ReadWriteLock getReadWriteLock(String object)
	{
		// CHM cannot use a null key
		String key = (object != null) ? object : EMPTY;
		
		ReadWriteLock lock = this.lockMap.get(key);
		
		if (lock == null)
		{
			lock = new SemaphoreReadWriteLock(new Semaphore(Integer.MAX_VALUE, this.fair), key);

			ReadWriteLock existing = this.lockMap.putIfAbsent(key, lock);
			
			if (existing != null)
			{
				lock = existing;
			}
		}
		
		return lock;
	}

	/**
	 * @see net.sf.hajdbc.Lifecycle#start()
	 */
	@Override
	public void start()
	{
		// Do nothing
	}

	/**
	 * @see net.sf.hajdbc.Lifecycle#stop()
	 */
	@Override
	public void stop()
	{
		// Do nothing
	}
}
