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
package net.sf.hajdbc.cache.simple;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.DatabaseClusterListener;
import net.sf.hajdbc.DatabaseProperties;
import net.sf.hajdbc.IdentifierNormalizer;
import net.sf.hajdbc.QualifiedNameFactory;
import net.sf.hajdbc.cache.DatabaseMetaDataCache;
import net.sf.hajdbc.cache.lazy.LazyDatabaseProperties;
import net.sf.hajdbc.dialect.Dialect;
import net.sf.hajdbc.dialect.StandardIdentifierNormalizer;
import net.sf.hajdbc.dialect.StandardQualifiedNameFactory;
import net.sf.hajdbc.state.DatabaseEvent;

/**
 * DatabaseMetaDataCache implementation that only caches the small, connection-independent
 * metadata core used by statement classification. Tables, sequences, schemas and types retain
 * the existing per-request lazy behavior.
 * 
 * @author Paul Ferraro
 * @since 2.0
 */
public class SimpleDatabaseMetaDataCache<Z, D extends Database<Z>> implements DatabaseMetaDataCache<Z, D>, DatabaseClusterListener
{
	private final DatabaseCluster<Z, D> cluster;
	private volatile Generation generation = new Generation();

	public SimpleDatabaseMetaDataCache(DatabaseCluster<Z, D> cluster)
	{
		this.cluster = cluster;
	}
	
	/**
	 * @see net.sf.hajdbc.cache.DatabaseMetaDataCache#flush()
	 */
	@Override
	public void flush()
	{
		this.generation = new Generation();
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.cache.DatabaseMetaDataCache#getDatabaseProperties(net.sf.hajdbc.Database, java.sql.Connection)
	 */
	@Override
	public DatabaseProperties getDatabaseProperties(D database, Connection connection) throws SQLException
	{
		DatabaseMetaData metaData = connection.getMetaData();
		Dialect dialect = this.cluster.getDialect();
		SimpleDatabaseMetaDataProvider provider = new SimpleDatabaseMetaDataProvider(metaData);
		Core core = this.core(this.generation, database.getId(), metaData, dialect);
		return (core != null) ? new SimpleDatabaseProperties(provider, dialect, core) : new LazyDatabaseProperties(provider, dialect);
	}

	@Override
	public void activated(DatabaseEvent event)
	{
		this.flush();
	}

	@Override
	public void deactivated(DatabaseEvent event)
	{
		this.flush();
	}

	private Core core(Generation current, String databaseId, final DatabaseMetaData metaData, final Dialect dialect) throws SQLException
	{
		FutureTask<Core> candidate = new FutureTask<Core>(() -> createCore(metaData, dialect));
		FutureTask<Core> task = current.tasks.putIfAbsent(databaseId, candidate);
		if (task == null)
		{
			task = candidate;
			candidate.run();
		}

		try
		{
			return task.get();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			current.tasks.remove(databaseId, task);
			SQLException exception = new SQLException("Interrupted while loading database metadata for " + databaseId);
			exception.initCause(e);
			throw exception;
		}
		catch (CancellationException e)
		{
			current.tasks.remove(databaseId, task);
			SQLException exception = new SQLException("Database metadata load was cancelled for " + databaseId);
			exception.initCause(e);
			throw exception;
		}
		catch (ExecutionException e)
		{
			current.tasks.remove(databaseId, task);
			Throwable cause = e.getCause();
			if (cause instanceof SQLException) throw (SQLException) cause;
			if (cause instanceof RuntimeException) throw (RuntimeException) cause;
			if (cause instanceof Error) throw (Error) cause;
			SQLException exception = new SQLException("Failed to load database metadata for " + databaseId);
			exception.initCause(cause);
			throw exception;
		}
	}

	private static Core createCore(DatabaseMetaData metaData, Dialect dialect) throws SQLException
	{
		boolean supportsSelectForUpdate = metaData.supportsSelectForUpdate();
		boolean locatorsUpdateCopy = metaData.locatorsUpdateCopy();
		IdentifierNormalizer normalizer = dialect.createIdentifierNormalizer(metaData);
		QualifiedNameFactory nameFactory = dialect.createQualifiedNameFactory(metaData, normalizer);

		// Only the standard implementations have a verified detached object graph. Unknown
		// third-party factories keep the original uncached behavior rather than risking a
		// retained Connection or DatabaseMetaData reference.
		if ((normalizer.getClass() != StandardIdentifierNormalizer.class) || (nameFactory.getClass() != StandardQualifiedNameFactory.class))
		{
			return null;
		}
		return new Core(supportsSelectForUpdate, locatorsUpdateCopy, nameFactory);
	}

	private static final class Generation
	{
		private final ConcurrentMap<String, FutureTask<Core>> tasks = new ConcurrentHashMap<String, FutureTask<Core>>();
	}

	private static final class Core
	{
		private final boolean supportsSelectForUpdate;
		private final boolean locatorsUpdateCopy;
		private final QualifiedNameFactory nameFactory;

		private Core(boolean supportsSelectForUpdate, boolean locatorsUpdateCopy, QualifiedNameFactory nameFactory)
		{
			this.supportsSelectForUpdate = supportsSelectForUpdate;
			this.locatorsUpdateCopy = locatorsUpdateCopy;
			this.nameFactory = nameFactory;
		}
	}

	private static final class SimpleDatabaseProperties extends LazyDatabaseProperties
	{
		private SimpleDatabaseProperties(SimpleDatabaseMetaDataProvider provider, Dialect dialect, Core core)
		{
			super(provider, dialect, core.supportsSelectForUpdate, core.locatorsUpdateCopy, core.nameFactory);
		}
	}
}
