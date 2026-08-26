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
package net.sf.hajdbc.sync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.DatabaseProperties;
import net.sf.hajdbc.balancer.Balancer;
import net.sf.hajdbc.cache.DatabaseMetaDataCache;
import net.sf.hajdbc.codec.Decoder;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

@SuppressWarnings("unchecked")
public class SynchronizationContextImplTest
{
	private DatabaseCluster<Object, Database<Object>> cluster;
	private Balancer<Object, Database<Object>> balancer;
	private Database<Object> sourceDatabase;
	private Database<Object> targetDatabase;
	private DatabaseMetaDataCache<Object, Database<Object>> cache;
	private DatabaseProperties sourceProperties;
	private DatabaseProperties targetProperties;
	private Connection sourceConnection;
	private Connection targetConnection;
	private ExecutorService executor;
	private ExecutorService sharedExecutor;
	private SynchronizationContextImpl.ExecutorFactory executorFactory;
	private ThreadFactory threadFactory;
	private Decoder decoder;
	private Object sourceConnectionSource;
	private Object targetConnectionSource;

	@Before
	public void before() throws Exception
	{
		this.cluster = mock(DatabaseCluster.class);
		this.balancer = mock(Balancer.class);
		this.sourceDatabase = mock(Database.class);
		this.targetDatabase = mock(Database.class);
		this.cache = mock(DatabaseMetaDataCache.class);
		this.sourceProperties = mock(DatabaseProperties.class);
		this.targetProperties = mock(DatabaseProperties.class);
		this.sourceConnection = mock(Connection.class);
		this.targetConnection = mock(Connection.class);
		this.executor = mock(ExecutorService.class);
		this.sharedExecutor = mock(ExecutorService.class);
		this.executorFactory = mock(SynchronizationContextImpl.ExecutorFactory.class);
		this.threadFactory = mock(ThreadFactory.class);
		this.decoder = mock(Decoder.class);
		this.sourceConnectionSource = new Object();
		this.targetConnectionSource = new Object();

		when(this.cluster.getBalancer()).thenReturn(this.balancer);
		when(this.balancer.next()).thenReturn(this.sourceDatabase);
		when(this.balancer.size()).thenReturn(2);
		when(this.cluster.getThreadFactory()).thenReturn(this.threadFactory);
		when(this.executorFactory.create(eq(2), same(this.threadFactory))).thenReturn(this.executor);
		when(this.cluster.getDatabaseMetaDataCache()).thenReturn(this.cache);
		when(this.cluster.getDecoder()).thenReturn(this.decoder);
		when(this.cluster.getExecutor()).thenReturn(this.sharedExecutor);

		when(this.targetDatabase.getConnectionSource()).thenReturn(this.targetConnectionSource);
		when(this.targetDatabase.decodePassword(this.decoder)).thenReturn("target-password");
		when(this.targetDatabase.connect(this.targetConnectionSource, "target-password")).thenReturn(this.targetConnection);
		when(this.targetConnection.getAutoCommit()).thenReturn(true);
		when(this.cache.getDatabaseProperties(this.targetDatabase, this.targetConnection)).thenReturn(this.targetProperties);

		when(this.sourceDatabase.getConnectionSource()).thenReturn(this.sourceConnectionSource);
		when(this.sourceDatabase.decodePassword(this.decoder)).thenReturn("source-password");
		when(this.sourceDatabase.connect(this.sourceConnectionSource, "source-password")).thenReturn(this.sourceConnection);
		when(this.sourceConnection.getAutoCommit()).thenReturn(false);
		when(this.cache.getDatabaseProperties(this.sourceDatabase, this.sourceConnection)).thenReturn(this.sourceProperties);
	}

	@Test
	public void targetConnectFailureStopsOwnedExecutor() throws Exception
	{
		SQLException failure = new SQLException("target connect");
		when(this.targetDatabase.connect(this.targetConnectionSource, "target-password")).thenThrow(failure);

		this.assertConstructionFailure(failure);

		verify(this.sourceDatabase, never()).connect(this.sourceConnectionSource, "source-password");
		verify(this.targetConnection, never()).close();
		verify(this.executor).shutdownNow();
	}

	@Test
	public void sourceConnectFailureClosesOwnedTargetConnection() throws Exception
	{
		SQLException failure = new SQLException("source connect");
		when(this.sourceDatabase.connect(this.sourceConnectionSource, "source-password")).thenThrow(failure);

		this.assertConstructionFailure(failure);

		verify(this.targetConnection).close();
		verify(this.targetConnection, never()).setAutoCommit(anyBoolean());
		verify(this.sourceConnection, never()).close();
		verify(this.executor).shutdownNow();
	}

	@Test
	public void autoCommitFailureClosesInFlightConnection() throws Exception
	{
		SQLException failure = new SQLException("auto commit");
		SQLException closeFailure = new SQLException("in-flight close");
		when(this.targetConnection.getAutoCommit()).thenThrow(failure);
		doThrow(closeFailure).when(this.targetConnection).close();

		SQLException thrown = this.assertConstructionFailure(failure);

		verify(this.targetConnection).close();
		verify(this.targetConnection, never()).setAutoCommit(anyBoolean());
		verify(this.executor).shutdownNow();
		assertSuppressed(thrown, closeFailure);
	}

	@Test
	public void runtimeFailureBeforeOwnershipClosesInFlightConnection() throws Exception
	{
		IllegalStateException failure = new IllegalStateException("before map ownership");
		when(this.targetConnection.getAutoCommit()).thenThrow(failure);

		try
		{
			this.createContext();
			fail("Expected construction failure");
		}
		catch (IllegalStateException e)
		{
			assertSame(failure, e);
		}

		verify(this.targetConnection).close();
		verify(this.executor).shutdownNow();
	}

	@Test
	public void targetMetadataFailureClosesCreatedConnection() throws Exception
	{
		SQLException failure = new SQLException("target metadata");
		when(this.cache.getDatabaseProperties(this.targetDatabase, this.targetConnection)).thenThrow(failure);

		this.assertConstructionFailure(failure);

		verify(this.targetConnection).close();
		verify(this.sourceDatabase, never()).connect(this.sourceConnectionSource, "source-password");
		verify(this.executor).shutdownNow();
	}

	@Test
	public void sourceMetadataFailureClosesAllCreatedConnections() throws Exception
	{
		SQLException failure = new SQLException("source metadata");
		when(this.cache.getDatabaseProperties(this.sourceDatabase, this.sourceConnection)).thenThrow(failure);

		this.assertConstructionFailure(failure);

		verify(this.targetConnection).close();
		verify(this.sourceConnection).close();
		verify(this.targetConnection, never()).setAutoCommit(anyBoolean());
		verify(this.sourceConnection, never()).setAutoCommit(anyBoolean());
		verify(this.executor).shutdownNow();
	}

	@Test
	public void cleanupFailuresAreSuppressedAndDoNotStopLaterCleanup() throws Exception
	{
		SQLException failure = new SQLException("source metadata");
		SQLException targetCloseFailure = new SQLException("target close");
		SQLException sourceCloseFailure = new SQLException("source close");
		IllegalStateException executorFailure = new IllegalStateException("executor shutdown");
		when(this.cache.getDatabaseProperties(this.sourceDatabase, this.sourceConnection)).thenThrow(failure);
		doThrow(targetCloseFailure).when(this.targetConnection).close();
		doThrow(sourceCloseFailure).when(this.sourceConnection).close();
		when(this.executor.shutdownNow()).thenThrow(executorFailure);

		SQLException thrown = this.assertConstructionFailure(failure);

		verify(this.targetConnection).close();
		verify(this.sourceConnection).close();
		verify(this.executor).shutdownNow();
		assertSuppressed(thrown, targetCloseFailure);
		assertSuppressed(thrown, sourceCloseFailure);
		assertSuppressed(thrown, executorFailure);
	}

	@Test
	public void initializationErrorRemainsPrimaryFailure() throws Exception
	{
		AssertionError failure = new AssertionError("target metadata error");
		when(this.cache.getDatabaseProperties(this.targetDatabase, this.targetConnection)).thenThrow(failure);

		try
		{
			this.createContext();
			fail("Expected construction error");
		}
		catch (AssertionError e)
		{
			assertSame(failure, e);
		}

		verify(this.targetConnection).close();
		verify(this.executor).shutdownNow();
	}

	@Test
	public void successfulContextRestoresAndClosesOwnedResources() throws Exception
	{
		SynchronizationContextImpl<Object, Database<Object>> context = this.createContext();

		assertSame(this.targetProperties, context.getTargetDatabaseProperties());
		assertSame(this.sourceProperties, context.getSourceDatabaseProperties());
		assertSame(this.executor, context.getExecutor());
		context.close();

		InOrder targetOrder = inOrder(this.targetConnection);
		targetOrder.verify(this.targetConnection).setAutoCommit(true);
		targetOrder.verify(this.targetConnection).close();
		InOrder sourceOrder = inOrder(this.sourceConnection);
		sourceOrder.verify(this.sourceConnection).setAutoCommit(false);
		sourceOrder.verify(this.sourceConnection).close();
		verify(this.targetConnection).close();
		verify(this.sourceConnection).close();
		verify(this.executorFactory).create(2, this.threadFactory);
		verify(this.executor).shutdown();
		verify(this.executor, never()).shutdownNow();
		verify(this.cluster, never()).getExecutor();
		verify(this.sharedExecutor, never()).shutdown();
		verify(this.sharedExecutor, never()).shutdownNow();
	}

	private SynchronizationContextImpl<Object, Database<Object>> createContext() throws SQLException
	{
		return new SynchronizationContextImpl<Object, Database<Object>>(this.cluster, this.targetDatabase, this.executorFactory);
	}

	private SQLException assertConstructionFailure(SQLException failure) throws Exception
	{
		try
		{
			this.createContext();
		}
		catch (SQLException e)
		{
			assertSame(failure, e);
			return e;
		}

		throw new AssertionError("Expected construction failure");
	}

	private static void assertSuppressed(Throwable failure, Throwable expected)
	{
		boolean found = false;
		for (Throwable suppressed: failure.getSuppressed())
		{
			if (suppressed == expected)
			{
				found = true;
			}
		}

		assertTrue("Missing suppressed exception: " + expected.getMessage(), found);
		assertFalse("Cleanup failure replaced the root cause", failure == expected);
	}
}
