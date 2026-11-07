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
	@Test
	@SuppressWarnings("unchecked")
	public void autoActivationLogsTargetAndRethrowsOriginalRuntimeFailure() throws Exception
	{
		ConcurrentMap<String, MockDatabase> databases = new ConcurrentHashMap<String, MockDatabase>();
		MockDatabase target = new MockDatabase("replica");
		databases.put(target.getId(), target);
		DatabaseClusterImpl<Void, MockDatabase> cluster = org.mockito.Mockito.spy(
				new DatabaseClusterImpl<Void, MockDatabase>("cluster", configuration(databases), null));
		org.mockito.Mockito.doReturn(null).when(cluster).getLocalDatabase();
		net.sf.hajdbc.state.health.ClusterHealth health = mock(net.sf.hajdbc.state.health.ClusterHealth.class);
		org.mockito.Mockito.doReturn(health).when(cluster).getClusterHealth();
		net.sf.hajdbc.balancer.Balancer<Void, MockDatabase> balancer = mock(net.sf.hajdbc.balancer.Balancer.class);
		org.mockito.Mockito.doReturn(balancer).when(cluster).getBalancer();
		IllegalStateException failure = new IllegalStateException("schema initialization failed");
		org.mockito.Mockito.doThrow(failure).when(cluster).activate(
				org.mockito.Mockito.same(target), org.mockito.Mockito.<net.sf.hajdbc.SynchronizationStrategy>any());
		net.sf.hajdbc.logging.Logger log = mock(net.sf.hajdbc.logging.Logger.class);
		Runnable task = cluster.new AutoActivationTask(log);
		// 下一次调度仍执行同一任务；只补日志，不改变异常身份或自动激活结果。
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				task.run();
				Assert.fail("Expected original failure");
			} catch (IllegalStateException e) {
				Assert.assertSame(failure, e);
			}
		}
		org.mockito.Mockito.verify(log, org.mockito.Mockito.times(2)).log(net.sf.hajdbc.logging.Level.WARN,
				failure, "Automatic database activation failed: cluster={0}, database={1}", "cluster", "replica");
		Assert.assertFalse(target.isActive());
	}

	@Test
	public void autoActivationLogsFailureBeforeTargetSelection()
	{
		DatabaseClusterImpl<Void, MockDatabase> cluster = org.mockito.Mockito.spy(
				new DatabaseClusterImpl<Void, MockDatabase>("cluster",
						configuration(new ConcurrentHashMap<String, MockDatabase>()), null));
		IllegalStateException failure = new IllegalStateException("local database lookup failed");
		org.mockito.Mockito.doThrow(failure).when(cluster).getLocalDatabase();
		net.sf.hajdbc.logging.Logger log = mock(net.sf.hajdbc.logging.Logger.class);
		try {
			cluster.new AutoActivationTask(log).run();
			Assert.fail("Expected original failure");
		} catch (IllegalStateException e) {
			Assert.assertSame(failure, e);
		}
		org.mockito.Mockito.verify(log).log(net.sf.hajdbc.logging.Level.WARN,
				failure, "Automatic database activation failed: cluster={0}, database={1}", "cluster", "<not-selected>");
	}

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

	@Test
	public void stopContinuesAcrossCleanupErrorsAndKeepsTheOriginalError() throws Exception {
		DatabaseClusterImpl<Void, MockDatabase> cluster = new DatabaseClusterImpl<>("cluster", configuration(new ConcurrentHashMap<>()), null);
		net.sf.hajdbc.state.StateManager state = mock(net.sf.hajdbc.state.StateManager.class);
		net.sf.hajdbc.lock.LockManager locks = mock(net.sf.hajdbc.lock.LockManager.class);
		java.util.concurrent.ExecutorService executor = mock(java.util.concurrent.ExecutorService.class);
		set(cluster, "stateManager", state); set(cluster, "lockManager", locks); set(cluster, "executor", executor);
		AssertionError root = new AssertionError("state stop");
		IllegalStateException lockFailure = new IllegalStateException("lock stop");
		IllegalArgumentException executorFailure = new IllegalArgumentException("executor stop");
		org.mockito.Mockito.doThrow(root).when(state).stop();
		org.mockito.Mockito.doThrow(lockFailure).when(locks).stop();
		when(executor.shutdownNow()).thenThrow(executorFailure);
		try { cluster.stop(); Assert.fail("Expected original cleanup Error"); }
		catch (AssertionError failure) {
			Assert.assertSame(root, failure);
			Assert.assertArrayEquals(new Throwable[] {lockFailure, executorFailure}, failure.getSuppressed());
		}
		org.mockito.Mockito.verify(locks).stop(); org.mockito.Mockito.verify(executor).shutdownNow();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void failedStartClosesOwnedLocksAndExecutorEvenWhenStateCleanupFails() throws Exception {
		DatabaseClusterConfiguration<Void, MockDatabase> config = configuration(new ConcurrentHashMap<>());
		net.sf.hajdbc.state.StateManager state = mock(net.sf.hajdbc.state.StateManager.class);
		net.sf.hajdbc.state.StateManagerFactory stateFactory = mock(net.sf.hajdbc.state.StateManagerFactory.class);
		net.sf.hajdbc.lock.LockManager locks = mock(net.sf.hajdbc.lock.LockManager.class);
		net.sf.hajdbc.lock.LockManagerFactory lockFactory = mock(net.sf.hajdbc.lock.LockManagerFactory.class);
		java.util.concurrent.ExecutorService executor = mock(java.util.concurrent.ExecutorService.class);
		net.sf.hajdbc.ExecutorServiceProvider provider = mock(net.sf.hajdbc.ExecutorServiceProvider.class);
		when(config.getStateManagerFactory()).thenReturn(stateFactory); when(stateFactory.createStateManager(org.mockito.Mockito.any())).thenReturn(state);
		when(config.getLockManagerFactory()).thenReturn(lockFactory); when(lockFactory.createLockManager()).thenReturn(locks);
		when(config.getExecutorProvider()).thenReturn(provider); when(provider.getExecutor(org.mockito.Mockito.any())).thenReturn(executor);
		java.io.IOException root = new java.io.IOException("persistent recovery record missing");
		IllegalStateException cleanup = new IllegalStateException("state cleanup");
		org.mockito.Mockito.doThrow(root).when(state).start(); org.mockito.Mockito.doThrow(cleanup).when(state).stop();
		DatabaseClusterImpl<Void, MockDatabase> cluster = new DatabaseClusterImpl<>("cluster", config, null);
		try { cluster.start(); Assert.fail("Expected failed startup"); }
		catch (java.io.IOException failure) { Assert.assertSame(root, failure); Assert.assertArrayEquals(new Throwable[] {cleanup}, failure.getSuppressed()); }
		org.mockito.Mockito.verify(locks).stop(); org.mockito.Mockito.verify(executor).shutdownNow();
	}

	private static void set(Object owner, String name, Object value) throws Exception {
		java.lang.reflect.Field field = DatabaseClusterImpl.class.getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
	}
}
