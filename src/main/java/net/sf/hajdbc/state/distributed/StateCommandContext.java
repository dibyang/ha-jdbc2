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

import java.util.Map;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.distributed.Remote;
import net.sf.hajdbc.durability.InvocationEvent;
import net.sf.hajdbc.durability.InvokerEvent;
import net.sf.hajdbc.state.StateManager;
import net.sf.hajdbc.state.health.ClusterHealth;

/**
 * @author paul
 *
 */
public interface StateCommandContext<Z, D extends Database<Z>>
{
	DatabaseCluster<Z, D> getDatabaseCluster();
	
	StateManager getLocalStateManager();
	
	Map<InvocationEvent, Map<String, InvokerEvent>> getRemoteInvokers(Remote remote);

	<R> Map<Member, R> executeAll(Command<R, StateCommandContext<Z, D>> command,
			Member... excludedMembers);

	<R> R execute(Command<R, StateCommandContext<Z, D>> command, Member member);

	ClusterHealth getHealth();
}
