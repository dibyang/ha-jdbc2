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
import net.sf.hajdbc.durability.InvocationEvent;
import net.sf.hajdbc.durability.InvokerEvent;

public class PostInvocationCommand<Z, D extends Database<Z>> extends InvocationCommand<Z, D>
{
	private static final long serialVersionUID = 6851682187122656940L;

	public PostInvocationCommand(RemoteInvocationDescriptor descriptor)
	{
		super(descriptor);
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.state.distributed.InvocationCommand#execute(java.util.Map, net.sf.hajdbc.durability.InvocationEvent)
	 */
	@Override
	protected void execute(Map<InvocationEvent, Map<String, InvokerEvent>> invokers, InvocationEvent event)
	{
		invokers.remove(event);
	}
}
