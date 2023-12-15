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
package net.sf.hajdbc.invocation;

import java.io.File;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.AbstractMap;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.sql.ProxyFactory;
import net.sf.hajdbc.util.Tracer;

/**
 * @author Paul Ferraro
 *
 */
public class ExistingResultsCollector implements InvokeOnManyInvocationStrategy.ResultsCollector
{
	protected final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Override
	public <Z, D extends Database<Z>, T, R, E extends Exception> Entry<SortedMap<D, R>, SortedMap<D, E>> collectResults(ProxyFactory<Z, D, T, E> factory, Invoker<Z, D, T, R, E> invoker)
	{
		SortedMap<D, R> resultMap = new TreeMap<D, R>();
		SortedMap<D, E> exceptionMap = new TreeMap<D, E>();
		if(Tracer.invoke.isTrace()) {
			logger.log(Level.INFO, "factory.entries().size={0}", factory.entries().size());
		}
		for (Map.Entry<D, T> entry: factory.entries())
		{
			D database = entry.getKey();
			
			try
			{
				resultMap.put(database, invoker.invoke(database, entry.getValue()));
			}
			catch (Exception e)
			{
				if(Tracer.invoke.isTrace()) {
					logger.log(Level.INFO, e);
				}
				// If this database was concurrently deactivated, just ignore the failure
				if (factory.getDatabaseCluster().getBalancer().contains(database))
				{
					exceptionMap.put(database, factory.getExceptionFactory().createException(e));
				}
			}
		}
		
		return new AbstractMap.SimpleImmutableEntry<SortedMap<D, R>, SortedMap<D, E>>(resultMap, exceptionMap);
	}
}
