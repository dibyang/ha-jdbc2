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
package net.sf.hajdbc.invocation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.ExceptionFactory;
import net.sf.hajdbc.Messages;
import net.sf.hajdbc.balancer.Balancer;
import net.sf.hajdbc.dialect.Dialect;
import net.sf.hajdbc.sql.ProxyFactory;
import net.sf.hajdbc.util.concurrent.SynchronousExecutor;

import org.junit.Before;
import org.junit.Test;

@SuppressWarnings("unchecked")
public class AllResultsCollectorTest
{
	private DatabaseCluster<Object, Database<Object>> cluster;
	private Balancer<Object, Database<Object>> balancer;
	private ProxyFactory<Object, Database<Object>, Object, SQLException> factory;
	private ExceptionFactory<SQLException> exceptionFactory;
	private Invoker<Object, Database<Object>, Object, String, SQLException> invoker;
	private AllResultsCollector.ExecutorProvider executorProvider;
	private SQLException noActiveDatabaseException;

	@Before
	public void before()
	{
		this.cluster = mock(DatabaseCluster.class);
		this.balancer = mock(Balancer.class);
		this.factory = mock(ProxyFactory.class);
		this.exceptionFactory = mock(ExceptionFactory.class);
		this.invoker = mock(Invoker.class);
		this.executorProvider = mock(AllResultsCollector.ExecutorProvider.class);
		this.noActiveDatabaseException = new SQLException("no active databases");

		when(this.factory.getDatabaseCluster()).thenReturn(this.cluster);
		when(this.factory.getExceptionFactory()).thenReturn(this.exceptionFactory);
		when(this.cluster.getBalancer()).thenReturn(this.balancer);
		when(this.exceptionFactory.createException(anyString())).thenReturn(this.noActiveDatabaseException);
	}

	@Test
	public void invokeOnAllFailsWithoutLookingUpExecutor() throws Exception
	{
		this.useEmptyBalancer();

		this.assertNoActiveDatabases(InvocationStrategies.INVOKE_ON_ALL);

		verify(this.cluster, never()).getExecutor();
	}

	@Test
	public void transactionInvokeOnAllFailsWithoutLookingUpExecutor() throws Exception
	{
		this.useEmptyBalancer();

		this.assertNoActiveDatabases(InvocationStrategies.TRANSACTION_INVOKE_ON_ALL);

		verify(this.cluster, never()).getTransactionMode();
		verify(this.cluster, never()).getExecutor();
	}

	@Test
	public void endTransactionInvokeOnAllFailsWithoutLookingUpExecutor() throws Exception
	{
		this.useEmptyBalancer();

		this.assertNoActiveDatabases(InvocationStrategies.END_TRANSACTION_INVOKE_ON_ALL);

		verify(this.cluster, never()).getTransactionMode();
		verify(this.cluster, never()).getExecutor();
	}

	@Test
	public void nonEmptyPrecheckStillFailsWhenIterationFormsNoInvocation() throws Exception
	{
		when(this.balancer.isEmpty()).thenReturn(false);
		when(this.balancer.size()).thenReturn(1);
		when(this.balancer.iterator()).thenReturn(Collections.<Database<Object>>emptySet().iterator());

		this.assertNoActiveDatabases(InvocationStrategies.INVOKE_ON_ALL);

		verify(this.cluster, never()).getExecutor();
	}

	@Test
	public void invokeOnExistingKeepsEmptyExistingObjectSet() throws Exception
	{
		when(this.factory.entries()).thenReturn(Collections.<Map.Entry<Database<Object>, Object>>emptySet());

		SortedMap<Database<Object>, String> results = InvocationStrategies.INVOKE_ON_EXISTING.invoke(this.factory, this.invoker);

		assertTrue(results.isEmpty());
		verifyZeroInteractions(this.invoker);
		verify(this.exceptionFactory, never()).createException(anyString());
	}

	@Test
	public void nonEmptyInvocationKeepsResult() throws Exception
	{
		Database<Object> database = this.useSingleDatabase();
		Object object = new Object();
		String expected = "result";
		ExecutorService executor = new SynchronousExecutor(Executors.newSingleThreadExecutor());

		when(this.factory.get(database)).thenReturn(object);
		when(this.executorProvider.getExecutor(this.cluster)).thenReturn(executor);
		when(this.invoker.invoke(database, object)).thenReturn(expected);

		try
		{
			SortedMap<Database<Object>, String> results = this.createStrategy().invoke(this.factory, this.invoker);

			assertEquals(1, results.size());
			assertSame(expected, results.get(database));
		}
		finally
		{
			executor.shutdownNow();
		}
	}

	@Test
	public void nonEmptyInvocationKeepsExceptionAggregation() throws Exception
	{
		Database<Object> database = this.useSingleDatabase();
		Object object = new Object();
		SQLException cause = new SQLException("cause");
		SQLException expected = new SQLException("mapped");
		Dialect dialect = mock(Dialect.class);
		ExecutorService executor = new SynchronousExecutor(Executors.newSingleThreadExecutor());

		when(this.factory.get(database)).thenReturn(object);
		when(this.executorProvider.getExecutor(this.cluster)).thenReturn(executor);
		when(this.invoker.invoke(database, object)).thenThrow(cause);
		when(this.exceptionFactory.createException(cause)).thenReturn(expected);
		when(this.cluster.getDialect()).thenReturn(dialect);
		when(this.exceptionFactory.indicatesFailure(expected, dialect)).thenReturn(false);
		when(this.exceptionFactory.equals(expected, expected)).thenReturn(true);

		try
		{
			this.createStrategy().invoke(this.factory, this.invoker);
			fail("Expected mapped invocation exception");
		}
		catch (SQLException e)
		{
			assertSame(expected, e);
		}
		finally
		{
			executor.shutdownNow();
		}
	}

	private void useEmptyBalancer()
	{
		when(this.balancer.size()).thenReturn(0);
		when(this.balancer.iterator()).thenReturn(Collections.<Database<Object>>emptySet().iterator());
	}

	private Database<Object> useSingleDatabase()
	{
		Database<Object> database = mock(Database.class);
		when(this.balancer.size()).thenReturn(1);
		when(this.balancer.iterator()).thenReturn(Collections.singleton(database).iterator());
		when(this.balancer.contains(database)).thenReturn(true);
		return database;
	}

	private InvocationStrategy createStrategy()
	{
		return new InvokeOnManyInvocationStrategy(new AllResultsCollector(this.executorProvider));
	}

	private void assertNoActiveDatabases(InvocationStrategy strategy) throws Exception
	{
		try
		{
			strategy.invoke(this.factory, this.invoker);
			fail("Expected no-active-databases exception");
		}
		catch (SQLException e)
		{
			assertSame(this.noActiveDatabaseException, e);
		}

		verify(this.exceptionFactory).createException(Messages.NO_ACTIVE_DATABASES.getMessage(this.cluster));
		verifyZeroInteractions(this.invoker);
	}
}
