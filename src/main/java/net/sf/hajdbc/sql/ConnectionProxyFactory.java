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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.invocation.Invoker;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.util.Resources;
import net.sf.hajdbc.util.reflect.Proxies;

/**
 * 
 * @author Paul Ferraro
 */
public class ConnectionProxyFactory<Z, D extends Database<Z>, P> extends AbstractSQLProxyFactory<Z, D, P, Connection>
{
	public ConnectionProxyFactory(P parentProxy, ProxyFactory<Z, D, P, SQLException> parent, Invoker<Z, D, P, Connection, SQLException> invoker, Map<D, Connection> map, TransactionContext<Z, D> context)
	{
		super(parentProxy, parent, invoker, map, context);
	}

	@Override
	public Connection getConnection(D database)
	{
		return this.get(database);
	}

	@Override
	public Connection createProxy()
	{
		return Proxies.createProxy(Connection.class, new ConnectionInvocationHandler<Z, D, P>(this));
	}

	@Override
	public void close(D database, Connection connection)
	{
		try
		{
			if (!connection.isClosed())
			{
				// Ensure that we rollback any current transaction to release any locks before closing
				// This is necessary if the connection is pooled
				if (!connection.getAutoCommit())
				{
					if (!connection.isReadOnly() || connection.getTransactionIsolation() >= Connection.TRANSACTION_REPEATABLE_READ)
					{
						connection.rollback();
					}
				}
			}
		}
		catch (SQLException e)
		{
			this.logger.log(Level.WARN, e);
		}

		Resources.close(connection);
	}
}
