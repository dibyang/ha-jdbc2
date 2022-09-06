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
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Map;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.invocation.Invoker;
import net.sf.hajdbc.util.reflect.Proxies;

/**
 * 
 * @author Paul Ferraro
 */
public class DatabaseMetaDataProxyFactory<Z, D extends Database<Z>> extends AbstractChildProxyFactory<Z, D, Connection, SQLException, DatabaseMetaData, SQLException>
{
	public DatabaseMetaDataProxyFactory(Connection parentProxy, ProxyFactory<Z, D, Connection, SQLException> parent, Invoker<Z, D, Connection, DatabaseMetaData, SQLException> invoker, Map<D, DatabaseMetaData> map)
	{
		super(parentProxy, parent, invoker, map, SQLException.class);
	}

	@Override
	public void close(D database, DatabaseMetaData object)
	{
		// Do nothing
	}

	@Override
	public DatabaseMetaData createProxy()
	{
		return Proxies.createProxy(DatabaseMetaData.class, new DatabaseMetaDataInvocationHandler<Z, D>(this));
	}
}
