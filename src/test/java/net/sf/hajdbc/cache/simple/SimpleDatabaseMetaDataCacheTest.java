package net.sf.hajdbc.cache.simple;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.DatabaseProperties;
import net.sf.hajdbc.IdentifierNormalizer;
import net.sf.hajdbc.MockDatabase;
import net.sf.hajdbc.QualifiedName;
import net.sf.hajdbc.QualifiedNameFactory;
import net.sf.hajdbc.dialect.Dialect;
import net.sf.hajdbc.dialect.StandardDialect;
import net.sf.hajdbc.state.DatabaseEvent;

import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Verifies that the simple cache only shares detached statement-classification metadata.
 */
public class SimpleDatabaseMetaDataCacheTest
{
	@Test
	public void repeatedStatementMetadataReadsLoadCoreOnce() throws Exception
	{
		Fixture fixture = new Fixture(new StandardDialect());
		when(fixture.metaData.supportsSelectForUpdate()).thenReturn(true);

		for (int i = 0; i < 100000; ++i)
		{
			assertTrue(fixture.cache.getDatabaseProperties(fixture.database, fixture.connection).supportsSelectForUpdate());
		}

		verify(fixture.metaData).getSQLKeywords();
		verify(fixture.metaData).supportsSelectForUpdate();
	}

	@Test
	public void concurrentFirstReadLoadsCoreOnce() throws Exception
	{
		final Fixture fixture = new Fixture(new StandardDialect());
		final int threads = 32;
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		when(fixture.metaData.getSQLKeywords()).thenAnswer(new Answer<String>()
		{
			@Override
			public String answer(InvocationOnMock invocation) throws Throwable
			{
				entered.countDown();
				assertTrue(release.await(5, TimeUnit.SECONDS));
				return "KEYWORD";
			}
		});

		ExecutorService executor = Executors.newFixedThreadPool(threads);
		List<Future<Boolean>> futures = new java.util.ArrayList<Future<Boolean>>(threads);
		try
		{
			for (int i = 0; i < threads; ++i)
			{
				futures.add(executor.submit(() -> fixture.cache.getDatabaseProperties(fixture.database, fixture.connection).supportsSelectForUpdate()));
			}
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			release.countDown();
			for (Future<Boolean> future: futures)
			{
				assertFalse(future.get(5, TimeUnit.SECONDS));
			}
			verify(fixture.metaData).getSQLKeywords();
		}
		finally
		{
			release.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void flushAndMembershipChangesInvalidateGeneration() throws Exception
	{
		Fixture fixture = new Fixture(new StandardDialect());
		fixture.cache.getDatabaseProperties(fixture.database, fixture.connection);
		assertEquals(1, fixture.sqlKeywordCalls.get());

		fixture.cache.flush();
		fixture.cache.getDatabaseProperties(fixture.database, fixture.connection);
		verify(fixture.metaData, times(2)).getSQLKeywords();

		fixture.cache.activated(new DatabaseEvent(fixture.database));
		fixture.cache.getDatabaseProperties(fixture.database, fixture.connection);
		assertEquals(3, fixture.sqlKeywordCalls.get());

		MockDatabase replacement = new MockDatabase(fixture.database.getId());
		fixture.cache.deactivated(new DatabaseEvent(replacement));
		fixture.cache.getDatabaseProperties(replacement, fixture.connection);
		assertEquals(4, fixture.sqlKeywordCalls.get());
	}

	@Test
	public void failedCoreLoadIsRemovedAndRetried() throws Exception
	{
		Fixture fixture = new Fixture(new StandardDialect());
		SQLException failure = new SQLException("first load failed");
		when(fixture.metaData.getSQLKeywords()).thenThrow(failure).thenReturn("KEYWORD");

		try
		{
			fixture.cache.getDatabaseProperties(fixture.database, fixture.connection);
			fail("Expected SQLException");
		}
		catch (SQLException e)
		{
			assertTrue(e == failure);
		}

		fixture.cache.getDatabaseProperties(fixture.database, fixture.connection);
		verify(fixture.metaData, times(2)).getSQLKeywords();
	}

	@Test
	public void tableMetadataRemainsPerReturnedPropertiesInstance() throws Exception
	{
		TrackingDialect dialect = new TrackingDialect();
		Fixture fixture = new Fixture(dialect);
		DatabaseProperties first = fixture.cache.getDatabaseProperties(fixture.database, fixture.connection);
		DatabaseProperties second = fixture.cache.getDatabaseProperties(fixture.database, fixture.connection);

		assertNotSame(first, second);
		assertEquals(1, first.getTables().size());
		assertEquals(1, first.getTables().size());
		assertEquals(1, second.getTables().size());
		assertEquals(2, dialect.tableLoads.get());
		assertEquals(1, fixture.sqlKeywordCalls.get());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void unknownFactoriesKeepOriginalUncachedConstruction() throws Exception
	{
		DatabaseCluster<Void, MockDatabase> cluster = mock(DatabaseCluster.class);
		Dialect dialect = mock(Dialect.class);
		IdentifierNormalizer normalizer = mock(IdentifierNormalizer.class);
		QualifiedNameFactory nameFactory = mock(QualifiedNameFactory.class);
		Connection connection = mock(Connection.class);
		DatabaseMetaData metaData = mock(DatabaseMetaData.class);
		MockDatabase database = new MockDatabase("database");
		when(cluster.getDialect()).thenReturn(dialect);
		when(connection.getMetaData()).thenReturn(metaData);
		when(dialect.createIdentifierNormalizer(metaData)).thenReturn(normalizer);
		when(dialect.createQualifiedNameFactory(metaData, normalizer)).thenReturn(nameFactory);
		SimpleDatabaseMetaDataCache<Void, MockDatabase> cache = new SimpleDatabaseMetaDataCache<Void, MockDatabase>(cluster);

		cache.getDatabaseProperties(database, connection);
		cache.getDatabaseProperties(database, connection);

		verify(dialect, times(3)).createIdentifierNormalizer(metaData);
		verify(dialect, times(3)).createQualifiedNameFactory(metaData, normalizer);
	}

	private static final class Fixture
	{
		private final MockDatabase database = new MockDatabase("database");
		private final Connection connection = mock(Connection.class);
		private final DatabaseMetaData metaData = mock(DatabaseMetaData.class);
		private final AtomicInteger sqlKeywordCalls = new AtomicInteger();
		private final SimpleDatabaseMetaDataCache<Void, MockDatabase> cache;

		@SuppressWarnings("unchecked")
		private Fixture(StandardDialect dialect) throws SQLException
		{
			DatabaseCluster<Void, MockDatabase> cluster = mock(DatabaseCluster.class);
			when(cluster.getDialect()).thenReturn(dialect);
			when(this.connection.getMetaData()).thenReturn(this.metaData);
			when(this.metaData.getExtraNameCharacters()).thenReturn("");
			when(this.metaData.getIdentifierQuoteString()).thenReturn("\"");
			when(this.metaData.getSQLKeywords()).thenAnswer(new Answer<String>()
			{
				@Override
				public String answer(InvocationOnMock invocation)
				{
					sqlKeywordCalls.incrementAndGet();
					return "KEYWORD";
				}
			});
			this.cache = new SimpleDatabaseMetaDataCache<Void, MockDatabase>(cluster);
		}
	}

	private static final class TrackingDialect extends StandardDialect
	{
		private final AtomicInteger tableLoads = new AtomicInteger();

		@Override
		public Collection<QualifiedName> getTables(DatabaseMetaData metaData, QualifiedNameFactory factory)
		{
			this.tableLoads.incrementAndGet();
			return Collections.singletonList(factory.createQualifiedName("schema", "table"));
		}
	}
}
