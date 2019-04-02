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

import java.io.File;
import java.io.FilenameFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.state.distributed.NodeState;
import net.sf.hajdbc.state.distributed.StateCommandContext;
import org.h2.tools.Restore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseRestoreCommand<Z, D extends Database<Z>> implements Command<String, StateCommandContext<Z, D>>
{
	private final static Logger logger = LoggerFactory.getLogger(DatabaseRestoreCommand.class);
	private static final long serialVersionUID = 1L;
	public static final int DEFAULT_MAX_BACKUP_COUNT = 10;
	private int getMaxBackupCount = DEFAULT_MAX_BACKUP_COUNT;
	static SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss");
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
					Path path = Paths.get(System.getProperty("user.dir"),"backup.bak");
					if (Files.exists(path)) {
						Files.delete(path);
					}
					Files.write(path,bytes);
					DatabaseCluster<Z, D> databaseCluster = context.getDatabaseCluster();
					D database = databaseCluster.getLocalDatabase();
					backOldData(databaseCluster, database);
					if(databaseCluster.restore(database,path.toFile())) {
						return database.getId();
					}
				}catch (Exception e){
          logger.warn("",e);
				}
			}
		}
		return null;
	}

	private void backOldData(DatabaseCluster<Z, D> databaseCluster, D database) {
		Path backupDir = Paths.get(System.getProperty("user.dir"),"backups");
		LinkedList<File> files = new LinkedList<>();
		File[] allFiles = backupDir.toFile().listFiles();
		if(allFiles!=null){
			for(File file : allFiles){
				if(file.getName().endsWith(".bak")){
					files.add(file);
				}
			}
		}
		Collections.sort(files, new Comparator<File>() {
			public int compare(File f1, File f2) {
				return f1.getName().compareTo(f2.getName());
			}
		});
		while(files.size()>getMaxBackupCount){
			File file = files.peekFirst();
			file.delete();
		}
		Path backup = Paths.get(backupDir.toFile().getPath(),"db_"+format.format(new Date())+".bak");
		databaseCluster.backup(database,backup.toFile());
    logger.info("backup old data:"+backup.toFile().getName());
	}
}
