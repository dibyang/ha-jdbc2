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
package net.sf.hajdbc;

import java.sql.SQLException;

import net.sf.hajdbc.state.health.NodeDatabaseRestoreListener;
import net.sf.hajdbc.state.health.NodeStateListener;


/**
 * @author Paul Ferraro
 */
public interface DatabaseClusterFactory<Z, D extends Database<Z>>
{
	DatabaseCluster<Z, D> createDatabaseCluster(String id, DatabaseClusterConfigurationFactory<Z, D> factory) throws SQLException;
	
	void addListener(String id, DatabaseClusterListener listener);
	
	void removeListener(String id, DatabaseClusterListener listener);
	
	void addSynchronizationListener(String id, SynchronizationListener listener);
	
	void removeSynchronizationListener(String id, SynchronizationListener listener);

	void addListener(String id, NodeStateListener listener);

	void removeListener(String id, NodeStateListener listener);

	void addListener(String id, NodeDatabaseRestoreListener listener);

	void removeListener(String id,NodeDatabaseRestoreListener listener);

}
