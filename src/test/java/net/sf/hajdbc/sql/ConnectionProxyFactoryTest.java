/*
 * HA-JDBC: High-Availability JDBC
 * Copyright (C) 2013  Paul Ferraro
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
package net.sf.hajdbc.sql;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.DatabaseProperties;
import net.sf.hajdbc.MockDatabase;
import net.sf.hajdbc.balancer.Balancer;
import net.sf.hajdbc.cache.DatabaseMetaDataCache;
import net.sf.hajdbc.dialect.Dialect;
import net.sf.hajdbc.invocation.Invoker;

import org.junit.Test;

public class ConnectionProxyFactoryTest
{
	@Test
	@SuppressWarnings("unchecked")
	public void nonSelectForUpdateDoesNotReadDatabaseMetaData() throws Exception
	{
		DatabaseCluster<Void, MockDatabase> cluster = mock(DatabaseCluster.class);
		Dialect dialect = mock(Dialect.class);
		DatabaseMetaDataCache<Void, MockDatabase> cache = mock(DatabaseMetaDataCache.class);
		when(cluster.getDialect()).thenReturn(dialect);
		when(cluster.getDatabaseMetaDataCache()).thenReturn(cache);
		when(dialect.isSelectForUpdate("SELECT * FROM test")).thenReturn(false);

		ConnectionProxyFactory<Void, MockDatabase, Object> factory = factory(cluster, new HashMap<MockDatabase, Connection>());

		assertFalse(factory.isSelectForUpdate("SELECT * FROM test"));
		verify(dialect).isSelectForUpdate("SELECT * FROM test");
		verifyZeroInteractions(cache);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void selectForUpdateReadsCurrentPrimaryDatabaseProperties() throws Exception
	{
		DatabaseCluster<Void, MockDatabase> cluster = mock(DatabaseCluster.class);
		Dialect dialect = mock(Dialect.class);
		DatabaseMetaDataCache<Void, MockDatabase> cache = mock(DatabaseMetaDataCache.class);
		Balancer<Void, MockDatabase> balancer = mock(Balancer.class);
		MockDatabase first = new MockDatabase("1");
		MockDatabase second = new MockDatabase("2");
		Connection firstConnection = mock(Connection.class);
		Connection secondConnection = mock(Connection.class);
		DatabaseProperties firstProperties = mock(DatabaseProperties.class);
		DatabaseProperties secondProperties = mock(DatabaseProperties.class);
		Map<MockDatabase, Connection> connections = new HashMap<MockDatabase, Connection>();
		connections.put(first, firstConnection);
		connections.put(second, secondConnection);

		when(cluster.getDialect()).thenReturn(dialect);
		when(cluster.getDatabaseMetaDataCache()).thenReturn(cache);
		when(cluster.getBalancer()).thenReturn(balancer);
		when(dialect.isSelectForUpdate("SELECT * FROM test FOR UPDATE")).thenReturn(true);
		when(balancer.primary()).thenReturn(first, second);
		when(cache.getDatabaseProperties(first, firstConnection)).thenReturn(firstProperties);
		when(cache.getDatabaseProperties(second, secondConnection)).thenReturn(secondProperties);
		when(firstProperties.supportsSelectForUpdate()).thenReturn(true);
		when(secondProperties.supportsSelectForUpdate()).thenReturn(false);

		ConnectionProxyFactory<Void, MockDatabase, Object> factory = factory(cluster, connections);

		assertTrue(factory.isSelectForUpdate("SELECT * FROM test FOR UPDATE"));
		assertFalse(factory.isSelectForUpdate("SELECT * FROM test FOR UPDATE"));
		verify(cache).getDatabaseProperties(first, firstConnection);
		verify(cache).getDatabaseProperties(second, secondConnection);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void dialectFailureDoesNotReadDatabaseMetaData() throws Exception
	{
		DatabaseCluster<Void, MockDatabase> cluster = mock(DatabaseCluster.class);
		Dialect dialect = mock(Dialect.class);
		DatabaseMetaDataCache<Void, MockDatabase> cache = mock(DatabaseMetaDataCache.class);
		SQLException failure = new SQLException("dialect failure");
		when(cluster.getDialect()).thenReturn(dialect);
		when(cluster.getDatabaseMetaDataCache()).thenReturn(cache);
		when(dialect.isSelectForUpdate("SELECT * FROM test")).thenThrow(failure);

		ConnectionProxyFactory<Void, MockDatabase, Object> factory = factory(cluster, new HashMap<MockDatabase, Connection>());

		try
		{
			factory.isSelectForUpdate("SELECT * FROM test");
			throw new AssertionError("Expected dialect failure");
		}
		catch (SQLException e)
		{
			assertSame(failure, e);
		}
		verifyZeroInteractions(cache);
	}

	@SuppressWarnings("unchecked")
	private static ConnectionProxyFactory<Void, MockDatabase, Object> factory(DatabaseCluster<Void, MockDatabase> cluster, Map<MockDatabase, Connection> connections)
	{
		ProxyFactory<Void, MockDatabase, Object, SQLException> parent = mock(ProxyFactory.class);
		Invoker<Void, MockDatabase, Object, Connection, SQLException> invoker = mock(Invoker.class);
		TransactionContext<Void, MockDatabase> context = mock(TransactionContext.class);
		when(parent.getDatabaseCluster()).thenReturn(cluster);
		return new ConnectionProxyFactory<Void, MockDatabase, Object>(new Object(), parent, invoker, connections, context);
	}
}
