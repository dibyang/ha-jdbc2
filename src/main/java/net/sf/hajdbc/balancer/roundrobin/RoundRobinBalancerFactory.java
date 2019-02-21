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
package net.sf.hajdbc.balancer.roundrobin;

import java.util.Set;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.balancer.Balancer;
import net.sf.hajdbc.balancer.BalancerFactory;
import net.sf.hajdbc.state.StateManager;

/**
 * Factory for creating a {@link RoundRobinBalancer}.
 * @author Paul Ferraro
 */
public class RoundRobinBalancerFactory implements BalancerFactory
{
	private static final long serialVersionUID = 9003494431296713142L;

	@Override
	public String getId()
	{
		return "round-robin";
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.balancer.BalancerFactory#createBalancer(Set, StateManager)
	 */
	@Override
	public <Z, D extends Database<Z>> Balancer<Z, D> createBalancer(Set<D> databases, StateManager stateManager)
	{
		return new RoundRobinBalancer<Z, D>(databases,stateManager);
	}
}
