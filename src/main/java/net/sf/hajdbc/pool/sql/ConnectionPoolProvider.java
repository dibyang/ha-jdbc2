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
package net.sf.hajdbc.pool.sql;

import java.sql.Connection;
import java.sql.SQLException;

import net.sf.hajdbc.pool.AbstractPoolProvider;
import net.sf.hajdbc.util.Resources;

/**
 * {@link Connection} object pool provider implementation.
 * @author Paul Ferraro
 */
public class ConnectionPoolProvider extends AbstractPoolProvider<Connection, SQLException>
{
	private final ConnectionFactory factory;
	
	public ConnectionPoolProvider(ConnectionFactory factory)
	{
		super(Connection.class, SQLException.class);
		
		this.factory = factory;
	}

	@Override
	public void close(Connection connection)
	{
		Resources.close(connection);
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.pool.PoolProvider#create()
	 */
	@Override
	public Connection create() throws SQLException
	{
		return this.factory.getConnection();
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.pool.PoolProvider#isValid(java.lang.Object)
	 */
	@Override
	public boolean isValid(Connection connection)
	{
		try
		{
			return connection.isValid(0);
		}
		catch (SQLException e)
		{
			return false;
		}
	}
}
