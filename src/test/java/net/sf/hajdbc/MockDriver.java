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
package net.sf.hajdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Mock driver that creates mock connections
 * @author  Paul Ferraro
 * @since   1.1
 */
public class MockDriver implements Driver
{
	private Connection connection;
	
	public MockDriver(Connection connection)
	{
		this.connection = connection;
	}
	
	/**
	 * @see java.sql.Driver#connect(java.lang.String, java.util.Properties)
	 */
	@Override
	public Connection connect(String url, Properties properties)
	{
		return this.connection;
	}

	/**
	 * @see java.sql.Driver#acceptsURL(java.lang.String)
	 */
	@Override
	public boolean acceptsURL(String url)
	{
		return url.startsWith("jdbc:mock:");
	}

	/**
	 * @see java.sql.Driver#getPropertyInfo(java.lang.String, java.util.Properties)
	 */
	@Override
	public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties)
	{
		return new DriverPropertyInfo[0];
	}

	/**
	 * @see java.sql.Driver#getMajorVersion()
	 */
	@Override
	public int getMajorVersion()
	{
		return 0;
	}

	/**
	 * @see java.sql.Driver#getMinorVersion()
	 */
	@Override
	public int getMinorVersion()
	{
		return 0;
	}

	/**
	 * @see java.sql.Driver#jdbcCompliant()
	 */
	@Override
	public boolean jdbcCompliant()
	{
		return false;
	}

	@Override
	public Logger getParentLogger() throws SQLFeatureNotSupportedException
	{
		return null;
	}
}
