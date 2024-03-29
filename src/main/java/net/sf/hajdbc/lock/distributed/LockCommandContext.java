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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.Lock;

import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.distributed.Remote;

/**
 * Execution context for lock commands.
 * @author Paul Ferraro
 */
public interface LockCommandContext
{
	Lock getDistibutedLock(RemoteLockDescriptor descriptor);

	Lock getLock(LockDescriptor descriptor);
	
	Map<LockDescriptor, Lock> getRemoteLocks(Remote remote);

	/**
	 *
	 * @param includeFree 是否包含已释放的锁
	 * @return
	 */
	Map<Member, Map<LockDescriptor, Lock>> getAllLocks(boolean includeFree);

}
