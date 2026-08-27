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
package net.sf.hajdbc.balancer;

import java.util.Collections;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.sf.hajdbc.MockDatabase;
import net.sf.hajdbc.balancer.load.LoadBalancerFactory;
import org.junit.Test;

import static org.junit.Assert.*;


/**
 * @author Paul Ferraro
 */
public class LoadBalancerTest extends AbstractBalancerTest
{
	public LoadBalancerTest()
	{
		super(new LoadBalancerFactory());
	}
	
	@Override
	public void next(Balancer<Void, MockDatabase> balancer)
	{
		assertSame(this.databases[2], balancer.next());
		
		ExecutorService executor = Executors.newFixedThreadPool(3);
		
		CountDownLatch latch = new CountDownLatch(2);
		WaitingInvoker invoker1 = new WaitingInvoker(latch);
		
		Future<Void> future1 = executor.submit(new InvocationTask(balancer, invoker1, this.databases[2]));
		Future<Void> future2 = executor.submit(new InvocationTask(balancer, invoker1, this.databases[2]));
		
		try
		{
			latch.await();
			
			assertSame(this.databases[1], balancer.next());
			
			latch = new CountDownLatch(1);
			WaitingInvoker invoker2 = new WaitingInvoker(latch);
			
			Future<Void> future = executor.submit(new InvocationTask(balancer, invoker2, this.databases[1]));
			
			latch.await();
			
			assertSame(this.databases[2], balancer.next());
			
			synchronized (invoker2)
			{
				invoker2.notifyAll();
			}
			
			this.complete(Collections.singletonList(future));
			
			assertSame(this.databases[1], balancer.next());
			
			synchronized (invoker1)
			{
				invoker1.notifyAll();
			}
			
			this.complete(Collections.singletonList(future1));
			this.complete(Collections.singletonList(future2));
			
			assertSame(this.databases[2], balancer.next());
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		finally
		{
			executor.shutdownNow();
		}
	}

	@Test
	public void databasesAreCachedInLocalFirstOrderAndCannotBeModified()
	{
		this.databases[2].setLocal(true);
		Balancer<Void, MockDatabase> balancer = this.factory.createBalancer(new HashSet<MockDatabase>(Arrays.asList(this.databases)));

		Set<MockDatabase> snapshot = balancer.getDatabases();
		assertArrayEquals(new MockDatabase[] { this.databases[2], this.databases[0], this.databases[1] }, snapshot.toArray(new MockDatabase[3]));
		assertSame(this.databases[2], balancer.primary());
		assertSame(this.databases[2], balancer.local());

		for (int i = 0; i < 100000; ++i)
		{
			assertSame(snapshot, balancer.getDatabases());
		}

		assertUnsupported(new Runnable()
		{
			@Override
			public void run()
			{
				snapshot.clear();
			}
		});
		assertUnsupported(new Runnable()
		{
			@Override
			public void run()
			{
				Iterator<MockDatabase> iterator = snapshot.iterator();
				iterator.next();
				iterator.remove();
			}
		});
	}

	@Test
	public void noOpChangesReuseSnapshot()
	{
		Balancer<Void, MockDatabase> balancer = this.factory.createBalancer(Collections.singleton(this.databases[1]));
		Set<MockDatabase> snapshot = balancer.getDatabases();

		assertFalse(balancer.add(this.databases[1]));
		assertSame(snapshot, balancer.getDatabases());
		assertFalse(balancer.addAll(Collections.singleton(this.databases[1])));
		assertSame(snapshot, balancer.getDatabases());
		assertFalse(balancer.remove(this.databases[2]));
		assertSame(snapshot, balancer.getDatabases());
	}

	@Test
	public void mixedAddAllPreservesExistingLoad() throws Exception
	{
		Balancer<Void, MockDatabase> balancer = this.factory.createBalancer(new HashSet<MockDatabase>(Arrays.asList(this.databases[1], this.databases[2])));
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch started = new CountDownLatch(1);
		WaitingInvoker invoker = new WaitingInvoker(started);
		Future<Void> future = executor.submit(new InvocationTask(balancer, invoker, this.databases[2]));

		try
		{
			assertTrue(started.await(5, TimeUnit.SECONDS));
			assertTrue(balancer.addAll(Arrays.asList(this.databases[2], this.databases[0])));
			assertSame(this.databases[1], balancer.next());
		}
		finally
		{
			try
			{
				synchronized (invoker)
				{
					invoker.notifyAll();
				}
				future.get(5, TimeUnit.SECONDS);
			}
			finally
			{
				executor.shutdownNow();
				assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
			}
		}
	}

	private static void assertUnsupported(Runnable action)
	{
		try
		{
			action.run();
			fail("Expected UnsupportedOperationException");
		}
		catch (UnsupportedOperationException e)
		{
			// Expected: the cached snapshot must not be mutable by callers.
		}
	}
}
