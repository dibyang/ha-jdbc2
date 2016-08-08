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
package net.sf.hajdbc.lock.distributed;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import net.sf.hajdbc.distributed.Command;

/**
 * @author Paul Ferraro
 *
 */
public class AcquireLockCommand implements Command<Boolean, LockCommandContext>
{
	private static final long serialVersionUID = 673191217118566395L;

	private final RemoteLockDescriptor descriptor;
	private final long timeout;

	public AcquireLockCommand(RemoteLockDescriptor descriptor, long timeout)
	{
		this.descriptor = descriptor;
		this.timeout = timeout;
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.distributed.Command#execute(java.lang.Object)
	 */
	@Override
	public Boolean execute(LockCommandContext context)
	{
		Lock lock = context.getLock(this.descriptor);
		
		try
		{
			boolean locked = lock.tryLock(this.timeout, TimeUnit.MILLISECONDS);
			
			if (locked)
			{
				Map<LockDescriptor, Lock> lockMap = context.getRemoteLocks(this.descriptor);
				
				synchronized (lockMap)
				{
					lockMap.put(this.descriptor, lock);
				}
			}
			
			return locked;
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * {@inheritDoc}
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString()
	{
		return String.format("%s(%s)", this.getClass().getSimpleName(), this.descriptor);
	}
}
