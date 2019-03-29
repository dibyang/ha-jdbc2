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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.state.DatabaseEvent;
import net.sf.hajdbc.state.distributed.NodeState;
import net.sf.hajdbc.state.distributed.StateCommandContext;
import org.h2.tools.Restore;
import org.h2.tools.RunScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class H2RestoreCommand<Z, D extends Database<Z>> implements Command<String, StateCommandContext<Z, D>>
{
	private final static Logger logger = LoggerFactory.getLogger(H2RestoreCommand.class);
	private static final long serialVersionUID = 1L;
	private byte[] bytes;

	public byte[] getBytes() {
		return bytes;
	}

	public void setBytes(byte[] bytes) {
		this.bytes = bytes;
	}

	@Override
	public String execute(StateCommandContext<Z, D> context) {
		if(bytes!=null){
			if(context.getHealth().getState().equals(NodeState.ready)){
				try {
					Path path = Paths.get(System.getProperty("user.dir"),"backup.zip");
					if (Files.exists(path)) {
						Files.delete(path);
					}
					Files.write(path,bytes);
					DatabaseCluster<Z, D> databaseCluster = context.getDatabaseCluster();
					D database = databaseCluster.getLocalDatabase();
					String location = database.getLocation();
					location=location.substring(14);
					int index = location.indexOf("/");
					location =location.substring(index+1);
					index = location.lastIndexOf("/");

					String dir=location.substring(0,index);
					String db=location.substring(index+1);
					logger.info("h2 restore dir="+dir+" db="+db);
					Restore.execute(path.toFile().getPath(),dir,db);
					return database.getId();
				}catch (Exception e){
					e.printStackTrace();
				}
			}
		}
		return null;
	}
}
