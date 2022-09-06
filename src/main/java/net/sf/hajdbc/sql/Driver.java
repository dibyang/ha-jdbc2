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
package net.sf.hajdbc.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import net.sf.hajdbc.AbstractDriver;
import net.sf.hajdbc.DatabaseClusterRegistry;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.DatabaseClusterConfigurationFactory;
import net.sf.hajdbc.DatabaseClusterFactory;
import net.sf.hajdbc.Messages;
import net.sf.hajdbc.invocation.InvocationStrategies;
import net.sf.hajdbc.invocation.Invoker;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;

/**
 * @author  Paul Ferraro
 */
public final class Driver extends AbstractDriver
{
	private static final Pattern URL_PATTERN = Pattern.compile("jdbc:ha-jdbc:(?://)?([^/]+)(?:/.+)?");
	private static final Logger logger = LoggerFactory.getLogger(Driver.class);

	
	static
	{
		try
		{
			DriverManager.registerDriver(new Driver());
		}
		catch (SQLException e)
		{
			logger.log(Level.ERROR, Messages.DRIVER_REGISTER_FAILED.getMessage(Driver.class.getName()), e);
		}
	}
	
	
	/**
	 * 
	 * @deprecated
	 * @see DatabaseClusterRegistry#stop(String)
	 * 
	 * @param id
	 * @throws SQLException
	 */
	public static void stop(String id) throws SQLException
	{
		DatabaseClusterRegistry.registry.stop(id);
	}
	
	/**
	 * 
	 * @deprecated
	 * @see DatabaseClusterRegistry#get(String)
	 * 
	 * @param id
	 * @return
	 * @throws SQLException
	 */
	public static DatabaseCluster<java.sql.Driver, DriverDatabase> get(String id) throws SQLException
	{
		return DatabaseClusterRegistry.registry.get(id);
	}

	/**
	 * @deprecated
	 * @see DatabaseClusterRegistry#setFactory(DatabaseClusterFactory)
	 * 
	 * @param databaseClusterFactory
	 */
	public static void setFactory(DatabaseClusterFactory<java.sql.Driver, DriverDatabase> databaseClusterFactory)
	{
		DatabaseClusterRegistry.registry.setFactory(databaseClusterFactory);
	}

	/**
	 * 
	 * @deprecated
	 * @see DatabaseClusterRegistry#setConfigurationFactory(String, DatabaseClusterConfigurationFactory)
	 * 
	 * @param id
	 * @param configurationFactory
	 */
	public static void setConfigurationFactory(String id, DatabaseClusterConfigurationFactory<java.sql.Driver, DriverDatabase> configurationFactory)
	{
		DatabaseClusterRegistry.registry.setConfigurationFactory(id,  configurationFactory);
	}
	
	/**
	 * 
	 * @deprecated
	 * @see DatabaseClusterRegistry#setTimeout(long, TimeUnit)
	 * 
	 * @param value
	 * @param unit
	 */
	public static void setTimeout(long value, TimeUnit unit)
	{
		DatabaseClusterRegistry.registry.setTimeout(value, unit);
	}
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.AbstractDriver#getUrlPattern()
	 */
	@Override
	protected Pattern getUrlPattern()
	{
		return URL_PATTERN;
	}

	/**
	 * {@inheritDoc}
	 * @see java.sql.Driver#connect(java.lang.String, java.util.Properties)
	 */
	@Override
	public Connection connect(String url, final Properties properties) throws SQLException
	{
		String id = this.parse(url);
		
		// JDBC spec compliance
		if (id == null) return null;
		
		DatabaseCluster<java.sql.Driver, DriverDatabase> cluster = DatabaseClusterRegistry.registry.get(id, properties);
		DriverProxyFactory driverFactory = new DriverProxyFactory(cluster);
		java.sql.Driver driver = driverFactory.createProxy();
		TransactionContext<java.sql.Driver, DriverDatabase> context = new LocalTransactionContext<java.sql.Driver, DriverDatabase>(cluster);

		DriverInvoker<Connection> invoker = new DriverInvoker<Connection>()
		{
			@Override
			public Connection invoke(DriverDatabase database, java.sql.Driver driver) throws SQLException
			{
				return driver.connect(database.getLocation(), properties);
			}
		};
		
		ConnectionProxyFactoryFactory<java.sql.Driver, DriverDatabase, java.sql.Driver> factory = new ConnectionProxyFactoryFactory<java.sql.Driver, DriverDatabase, java.sql.Driver>(context);
		return factory.createProxyFactory(driver, driverFactory, invoker, InvocationStrategies.INVOKE_ON_ALL.invoke(driverFactory, invoker)).createProxy();
	}
	
	/**
	 * {@inheritDoc}
	 * @see Driver#getPropertyInfo(java.lang.String, java.util.Properties)
	 */
	@Override
	public DriverPropertyInfo[] getPropertyInfo(String url, final Properties properties) throws SQLException
	{
		String id = this.parse(url);
		
		// JDBC spec compliance
		if (id == null) return null;
		
		DatabaseCluster<java.sql.Driver, DriverDatabase> cluster = DatabaseClusterRegistry.registry.get(id, properties);
		DriverProxyFactory map = new DriverProxyFactory(cluster);
		
		DriverInvoker<DriverPropertyInfo[]> invoker = new DriverInvoker<DriverPropertyInfo[]>()
		{
			@Override
			public DriverPropertyInfo[] invoke(DriverDatabase database, java.sql.Driver driver) throws SQLException
			{
				return driver.getPropertyInfo(database.getLocation(), properties);
			}
		};
		
		SortedMap<DriverDatabase, DriverPropertyInfo[]> results = InvocationStrategies.INVOKE_ON_ANY.invoke(map, invoker);
		return results.get(results.firstKey());
	}

	/**
	 * @see java.sql.Driver#getParentLogger()
	 */
	@Override
	public java.util.logging.Logger getParentLogger()
	{
		return java.util.logging.Logger.getGlobal();
	}

	private interface DriverInvoker<R> extends Invoker<java.sql.Driver, DriverDatabase, java.sql.Driver, R, SQLException>
	{
	}
}
