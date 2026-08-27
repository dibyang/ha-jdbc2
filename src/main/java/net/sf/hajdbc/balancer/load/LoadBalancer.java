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
package net.sf.hajdbc.balancer.load;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.balancer.AbstractBalancer;
import net.sf.hajdbc.invocation.Invoker;

/**
 * Balancer implementation whose {@link #next()} implementation returns the database with the least load.
 *
 * @author  Paul Ferraro
 * @param <D> either java.sql.Driver or javax.sql.DataSource
 */
public class LoadBalancer<Z, D extends Database<Z>> extends AbstractBalancer<Z, D>
{
	private final Lock lock = new ReentrantLock();

	/**
	 * 负载映射和 local-first 只读集合必须作为同一代状态一起发布。
	 */
	private volatile BalancerState<D> state;

	private final Comparator<Map.Entry<D, AtomicInteger>> comparator = new Comparator<Map.Entry<D, AtomicInteger>>()
	{
		@Override
		public int compare(Map.Entry<D, AtomicInteger> mapEntry1, Map.Entry<D, AtomicInteger> mapEntry2)
		{
			D database1 = mapEntry1.getKey();
			D database2 = mapEntry2.getKey();

			float load1 = mapEntry1.getValue().get();
			float load2 = mapEntry2.getValue().get();
			
			int weight1 = database1.getWeight();
			int weight2 = database2.getWeight();
			
			// If weights are the same, we can simply compare the loads
			if (weight1 == weight2)
			{
				return Float.compare(load1, load2);
			}
			
			float weightedLoad1 = (weight1 != 0) ? (load1 / weight1) : Float.POSITIVE_INFINITY;
			float weightedLoad2 = (weight2 != 0) ? (load2 / weight2) : Float.POSITIVE_INFINITY;
			
			return Float.compare(weightedLoad1, weightedLoad2);
		}
	};

	/**
	 * Constructs a new LoadBalancer
	 * @param databases
	 */
	public LoadBalancer(Set<D> databases)
	{
		SortedMap<D, AtomicInteger> map = new TreeMap<D, AtomicInteger>();
		for (D database: databases)
		{
			map.put(database, new AtomicInteger(1));
		}
		this.state = this.createState(map);
	}
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.balancer.Balancer#primary()
	 */
	@Override
	public D primary()
	{
		return this.state.primary;
	}

	@Override
	public D local() {
		return this.state.local;
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.balancer.AbstractBalancer#getDatabases()
	 */
	@Override
	public Set<D> getDatabases()
	{
		// 共享快照必须只读，否则调用方会破坏 balancer 的同代状态。
		return this.state.databases;
	}

	/**
	 * {@inheritDoc}
	 * @see java.util.Set#addAll(java.util.Collection)
	 */
	@Override
	public boolean addAll(Collection<? extends D> databases)
	{
		this.lock.lock();
		
		try
		{
			SortedMap<D, AtomicInteger> addMap = new TreeMap<D, AtomicInteger>(this.state.databaseMap);
			
			boolean added = false;
			
			for (D database: databases)
			{
				if (!addMap.containsKey(database))
				{
					addMap.put(database, new AtomicInteger(1));
					added = true;
				}
			}
			
			if (added)
			{
				this.state = this.createState(addMap);
			}
			
			return added;
		}
		finally
		{
			this.lock.unlock();
		}
	}

	/**
	 * {@inheritDoc}
	 * @see java.util.Set#removeAll(java.util.Collection)
	 */
	@Override
	public boolean removeAll(Collection<?> databases)
	{
		this.lock.lock();
		
		try
		{
			SortedMap<D, AtomicInteger> map = new TreeMap<D, AtomicInteger>(this.state.databaseMap);
			
			boolean removed = map.keySet().removeAll(databases);

			if (removed)
			{
				this.state = this.createState(map);
			}
			
			return removed;
		}
		finally
		{
			this.lock.unlock();
		}
	}

	/**
	 * {@inheritDoc}
	 * @see java.util.Set#retainAll(java.util.Collection)
	 */
	@Override
	public boolean retainAll(Collection<?> databases)
	{
		this.lock.lock();
		
		try
		{
			SortedMap<D, AtomicInteger> map = new TreeMap<D, AtomicInteger>(this.state.databaseMap);
			
			boolean retained = map.keySet().retainAll(databases);

			if (retained)
			{
				this.state = this.createState(map);
			}
			
			return retained;
		}
		finally
		{
			this.lock.unlock();
		}
	}

	/**
	 * {@inheritDoc}
	 * @see java.util.Set#clear()
	 */
	@Override
	public void clear()
	{
		this.lock.lock();
		
		try
		{
			if (!this.state.databaseMap.isEmpty())
			{
				this.state = this.createState(new TreeMap<D, AtomicInteger>());
			}
		}
		finally
		{
			this.lock.unlock();
		}
	}

	/**
	 * {@inheritDoc}
	 * @see java.util.Set#remove(java.lang.Object)
	 */
	@Override
	public boolean remove(Object database)
	{
		this.lock.lock();
		
		try
		{
			boolean remove = this.state.databaseMap.containsKey(database);
			
			if (remove)
			{
				SortedMap<D, AtomicInteger> map = new TreeMap<D, AtomicInteger>(this.state.databaseMap);
				map.remove(database);
				this.state = this.createState(map);
			}
			
			return remove;
		}
		finally
		{
			this.lock.unlock();
		}
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.balancer.Balancer#next()
	 */
	@Override
	public D next()
	{
		Set<Map.Entry<D, AtomicInteger>> entrySet = this.state.databaseMap.entrySet();
		
		return !entrySet.isEmpty() ? java.util.Collections.min(entrySet, this.comparator).getKey() : null;
	}

	/**
	 * {@inheritDoc}
	 * @see java.util.Set#add(java.lang.Object)
	 */
	@Override
	public boolean add(D database)
	{
		this.lock.lock();
		
		try
		{
			boolean add = !this.state.databaseMap.containsKey(database);
			
			if (add)
			{
				AtomicInteger load = new AtomicInteger(1);
				
				SortedMap<D, AtomicInteger> map = new TreeMap<D, AtomicInteger>(this.state.databaseMap);
				map.put(database, load);
				this.state = this.createState(map);
			}
			
			return add;
		}
		finally
		{
			this.lock.unlock();
		}
	}
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.balancer.Balancer#invoke(net.sf.hajdbc.invocation.Invoker, net.sf.hajdbc.Database, java.lang.Object)
	 */
	@Override
	public <T, R, E extends Exception> R invoke(Invoker<Z, D, T, R, E> invoker, D database, T object) throws E
	{
		AtomicInteger load = this.state.databaseMap.get(database);
		
		if (load != null)
		{
			load.incrementAndGet();
		}
		
		try
		{
			return invoker.invoke(database, object);
		}
		finally
		{
			if (load != null)
			{
				load.decrementAndGet();
			}
		}
	}

	/**
	 * 在写锁内构造完整状态，保证读线程不会观察到跨代的映射和顺序集合。
	 */
	private BalancerState<D> createState(SortedMap<D, AtomicInteger> source)
	{
		SortedMap<D, AtomicInteger> map = java.util.Collections.unmodifiableSortedMap(source);
		LinkedHashSet<D> ordered = new LinkedHashSet<D>(map.size());
		D local = null;

		for (D database: map.keySet())
		{
			if (database.isLocal())
			{
				if (local == null)
				{
					local = database;
				}
				ordered.add(database);
			}
		}
		for (D database: map.keySet())
		{
			if (!database.isLocal())
			{
				ordered.add(database);
			}
		}

		Set<D> databases = java.util.Collections.unmodifiableSet(ordered);
		D primary = ordered.isEmpty() ? null : ordered.iterator().next();
		return new BalancerState<D>(map, databases, primary, local);
	}

	private static final class BalancerState<D>
	{
		private final SortedMap<D, AtomicInteger> databaseMap;
		private final Set<D> databases;
		private final D primary;
		private final D local;

		private BalancerState(SortedMap<D, AtomicInteger> databaseMap, Set<D> databases, D primary, D local)
		{
			this.databaseMap = databaseMap;
			this.databases = databases;
			this.primary = primary;
			this.local = local;
		}
	}
}
