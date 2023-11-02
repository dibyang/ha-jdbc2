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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.TreeMap;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.DatabaseClusterListener;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.state.DatabaseEvent;
import net.sf.hajdbc.util.StopWatch;

/**
 * 
 * @author Paul Ferraro
 */
public abstract class AbstractRootProxyFactory<Z, D extends Database<Z>> extends AbstractProxyFactory<Z, D, SQLException, Z, SQLException> implements RootProxyFactory<Z, D>, DatabaseClusterListener
{
	protected AbstractRootProxyFactory(DatabaseCluster<Z, D> cluster)
	{
		super(cluster, new TreeMap<D, Z>(), SQLException.class);
		
		for (D database: cluster.getBalancer())
		{
			this.get(database);
		}
		
		cluster.addListener(this);
	}

	@Override
	protected Z create(D database)
	{
		return database.getConnectionSource();
	}

	@Override
	public void activated(DatabaseEvent event)
	{
		// Do nothing
	}

	@Override
	public synchronized void deactivated(DatabaseEvent event)
	{
		D database = this.getDatabaseCluster().getDatabase(event.getSource());
		
		for (ChildProxyFactory<Z, D, Z, SQLException, ?, ? extends Exception> child: this.children())
		{
			this.getDatabaseCluster().getExecutor().submit(()->{
				StopWatch stopWatch = StopWatch.createStarted();
				child.close(database);
				logger.log(Level.INFO, "child {0} close cost time:{1}", child, stopWatch.toString());
			});
		}
	}

}
