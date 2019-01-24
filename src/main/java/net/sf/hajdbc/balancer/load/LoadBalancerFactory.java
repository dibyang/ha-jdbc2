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
package net.sf.hajdbc.balancer.load;

import java.util.Set;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.balancer.Balancer;
import net.sf.hajdbc.balancer.BalancerFactory;
import net.sf.hajdbc.balancer.DatabaseChecker;

/**
 * Factory for creating a {@link LoadBalancer}
 * @author Paul Ferraro
 */
public class LoadBalancerFactory implements BalancerFactory
{
	private static final long serialVersionUID = -2785311844872496108L;

	@Override
	public String getId()
	{
		return "load";
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.balancer.BalancerFactory#createBalancer(Set, DatabaseChecker)
	 */
	@Override
	public <Z, D extends Database<Z>> Balancer<Z, D> createBalancer(Set<D> databases,DatabaseChecker checker)
	{
		return new LoadBalancer<Z, D>(databases,checker);
	}
}
