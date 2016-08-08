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
package net.sf.hajdbc.lock;

import java.util.concurrent.locks.Lock;

import net.sf.hajdbc.Lifecycle;

/**
 * Manages a set of named read/write locks.  A global lock is represented by a null object.
 * Obtaining a named read or write lock should implicitly obtain a global read lock.
 * Consequently, all named locks are blocked if a global write lock is obtained.
 * @author Paul Ferraro
 */
public interface LockManager extends Lifecycle
{
	/**
	 * Obtains a named read lock.
	 * @param object an object to lock
	 * @return a read lock
	 */
	Lock readLock(String object);

	/**
	 * Obtains a named write lock.
	 * @param object an object to lock
	 * @return a write lock
	 */
	Lock writeLock(String object);
}
