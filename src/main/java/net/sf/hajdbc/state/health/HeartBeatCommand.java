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
package net.sf.hajdbc.state.health;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.state.distributed.StateCommandContext;
import org.joda.time.DateTime;

public class HeartBeatCommand<Z, D extends Database<Z>> implements Command<Void, StateCommandContext<Z, D>>
{
	private static final long serialVersionUID = 1L;
  private long sendTime = 0;

	public HeartBeatCommand preSend() {
		DateTime now = new DateTime();
		this.sendTime = now.getMillis();
		return this;
	}

	@Override
	public Void execute(StateCommandContext<Z, D> context) {
		ClusterHealth health = context.getHealth();
		if(health!=null){
			health.receiveHeartbeat(sendTime);
		}
		return null;
	}
}
