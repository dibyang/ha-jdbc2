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
package net.sf.hajdbc.state.distributed;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.state.DatabaseEvent;
import net.sf.hajdbc.state.StateManager;

/**
 * @author paul
 *
 */
public abstract class StateCommand<Z, D extends Database<Z>> implements Command<Boolean, StateCommandContext<Z, D>>
{
	private static final long serialVersionUID = 8689116981769588205L;

	private final DatabaseEvent event;
	
	protected StateCommand(DatabaseEvent event)
	{
		this.event = event;
	}
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.distributed.Command#execute(java.lang.Object)
	 */
	@Override
	public Boolean execute(StateCommandContext<Z, D> context)
	{
		DatabaseCluster<Z, D> cluster = context.getDatabaseCluster();
		
		return this.execute(cluster, context.getLocalStateManager(), cluster.getDatabase(this.event.getSource()));
	}

	protected abstract boolean execute(DatabaseCluster<Z, D> cluster, StateManager stateManager, D database);

	/**
	 * {@inheritDoc}
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString()
	{
		return String.format("%s(%s)", this.getClass().getSimpleName(), this.event);
	}
}
