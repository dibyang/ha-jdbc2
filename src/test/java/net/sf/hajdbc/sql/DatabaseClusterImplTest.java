package net.sf.hajdbc.sql;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;

import net.sf.hajdbc.DatabaseClusterConfiguration;
import net.sf.hajdbc.LocalDatabaseTestSupport;
import net.sf.hajdbc.MockDatabase;
import net.sf.hajdbc.balancer.simple.SimpleBalancerFactory;
import net.sf.hajdbc.cache.simple.SimpleDatabaseMetaDataCacheFactory;
import net.sf.hajdbc.codec.DecoderFactory;
import net.sf.hajdbc.codec.SimpleCodecFactory;
import net.sf.hajdbc.dialect.StandardDialectFactory;
import net.sf.hajdbc.durability.none.NoDurabilityFactory;
import net.sf.hajdbc.io.simple.SimpleInputSinkProvider;
import net.sf.hajdbc.lock.semaphore.SemaphoreLockManagerFactory;
import net.sf.hajdbc.state.simple.SimpleStateManagerFactory;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DatabaseClusterImplTest
{
	@After
	public void clearStartupProperties()
	{
		System.clearProperty(DatabaseClusterImpl.STARTUP_LOCAL_DATABASE_TIMEOUT_PROPERTY);
		System.clearProperty(DatabaseClusterImpl.STARTUP_ACTIVE_DATABASE_TIMEOUT_PROPERTY);
		System.clearProperty(DatabaseClusterImpl.STARTUP_RETRY_INTERVAL_PROPERTY);
	}

	@Test
	public void getLocalDatabaseTimesOutWhenNoConfiguredDatabaseMatchesLocalHost()
	{
		System.setProperty(DatabaseClusterImpl.STARTUP_LOCAL_DATABASE_TIMEOUT_PROPERTY, "10");
		System.setProperty(DatabaseClusterImpl.STARTUP_RETRY_INTERVAL_PROPERTY, "1");
		ConcurrentMap<String, MockDatabase> databases = new ConcurrentHashMap<String, MockDatabase>();
		MockDatabase database = new MockDatabase("db1");
		database.setIp(LocalDatabaseTestSupport.remoteIp());
		databases.put(database.getId(), database);

		DatabaseClusterImpl<Void, MockDatabase> cluster = new DatabaseClusterImpl<Void, MockDatabase>("cluster", configuration(databases), null);

		try
		{
			cluster.getLocalDatabase();
			Assert.fail("Expected local database lookup to time out");
		}
		catch (IllegalStateException e)
		{
			Assert.assertTrue(e.getMessage().contains("Timed out waiting for local database"));
		}
	}

	@Test
	public void startTimesOutWhenNoDatabaseCanBecomeActive() throws Exception
	{
		System.setProperty(DatabaseClusterImpl.STARTUP_ACTIVE_DATABASE_TIMEOUT_PROPERTY, "10");
		System.setProperty(DatabaseClusterImpl.STARTUP_RETRY_INTERVAL_PROPERTY, "1");
		ConcurrentMap<String, MockDatabase> databases = new ConcurrentHashMap<String, MockDatabase>();
		MockDatabase database = new MockDatabase("db1");
		database.setIp(LocalDatabaseTestSupport.localIp());
		databases.put(database.getId(), database);
		DatabaseClusterImpl<Void, MockDatabase> cluster = new DatabaseClusterImpl<Void, MockDatabase>("cluster", configuration(databases), null);

		try
		{
			cluster.start();
			Assert.fail("Expected startup to time out when no database can be activated");
		}
		catch (IllegalStateException e)
		{
			Assert.assertTrue(e.getMessage().contains("Timed out waiting for active database"));
		}
		finally
		{
			cluster.stop();
		}
	}

	@SuppressWarnings("unchecked")
	private static DatabaseClusterConfiguration<Void, MockDatabase> configuration(ConcurrentMap<String, MockDatabase> databases)
	{
		DatabaseClusterConfiguration<Void, MockDatabase> configuration = mock(DatabaseClusterConfiguration.class);
		DecoderFactory decoderFactory = mock(DecoderFactory.class);
		try
		{
			when(decoderFactory.createDecoder("cluster")).thenReturn(new SimpleCodecFactory());
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
		when(configuration.getDatabaseMap()).thenReturn(databases);
		when(configuration.getDecoderFactory()).thenReturn(decoderFactory);
		when(configuration.getLockManagerFactory()).thenReturn(new SemaphoreLockManagerFactory());
		when(configuration.getStateManagerFactory()).thenReturn(new SimpleStateManagerFactory());
		when(configuration.getBalancerFactory()).thenReturn(new SimpleBalancerFactory());
		when(configuration.getDialectFactory()).thenReturn(new StandardDialectFactory());
		when(configuration.getDurabilityFactory()).thenReturn(new NoDurabilityFactory());
		when(configuration.getExecutorProvider()).thenReturn(new DefaultExecutorServiceProvider());
		when(configuration.getThreadFactory()).thenReturn(Executors.defaultThreadFactory());
		when(configuration.getInputSinkProvider()).thenReturn(new SimpleInputSinkProvider());
		when(configuration.getDatabaseMetaDataCacheFactory()).thenReturn(new SimpleDatabaseMetaDataCacheFactory());
		return configuration;
	}
}
