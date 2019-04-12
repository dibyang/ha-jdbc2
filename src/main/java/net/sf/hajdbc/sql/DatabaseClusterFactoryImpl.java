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
package net.sf.hajdbc.sql;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.sf.hajdbc.*;
import net.sf.hajdbc.state.health.NodeDatabaseRestoreListener;
import net.sf.hajdbc.state.health.NodeStateListener;

/**
 * @author Paul Ferraro
 */
public class DatabaseClusterFactoryImpl<Z, D extends Database<Z>> implements DatabaseClusterFactory<Z, D>
{
	Map<String, Set<DatabaseClusterListener>> databaseClusterListeners = new HashMap<String, Set<DatabaseClusterListener>>();

	Map<String, Set<SynchronizationListener>> synchronizationListeners = new HashMap<String, Set<SynchronizationListener>>();

	Map<String, Set<NodeStateListener>> nodeStateListeners = new HashMap<String, Set<NodeStateListener>>();

	Map<String, Set<NodeDatabaseRestoreListener>> nodeDatabaseRestoreListeners = new HashMap<String, Set<NodeDatabaseRestoreListener>>();

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.DatabaseClusterFactory#createDatabaseCluster(java.lang.String, net.sf.hajdbc.DatabaseClusterConfigurationFactory)
	 */
	@Override
	public DatabaseCluster<Z, D> createDatabaseCluster(String id, DatabaseClusterConfigurationFactory<Z, D> factory) throws SQLException
	{
		DatabaseCluster<Z, D> databaseCluster = new DatabaseClusterImpl<Z, D>(id, factory.createConfiguration(), factory);
		addDatabaseClusterListeners(id, databaseCluster);
		addSynchronizationListeners(id, databaseCluster);
		addNodeStateListeners(id, databaseCluster);
		addNodeDatabaseRestoreListeners(id,databaseCluster);
		return databaseCluster;
	}

	private void addDatabaseClusterListeners(String id,
			DatabaseCluster<Z, D> databaseCluster) {
		Set<DatabaseClusterListener> listeners = databaseClusterListeners.get(id);
		if(listeners!=null){
			for(DatabaseClusterListener listener:listeners){
				databaseCluster.addListener(listener);
			}
		}
	}
	
	private void addSynchronizationListeners(String id,
			DatabaseCluster<Z, D> databaseCluster) {
		Set<SynchronizationListener> listeners = synchronizationListeners.get(id);
		if(listeners!=null){
			for(SynchronizationListener listener:listeners){
				databaseCluster.addSynchronizationListener(listener);
			}
		}
	}

	private void addNodeStateListeners(String id,
																			DatabaseCluster<Z, D> databaseCluster) {
	Set<NodeStateListener> listeners = nodeStateListeners.get(id);
	if(listeners!=null){
		for(NodeStateListener listener:listeners){
			databaseCluster.addListener(listener);
		}
	}
}

	private void addNodeDatabaseRestoreListeners(String id,
																		 DatabaseCluster<Z, D> databaseCluster) {
		Set<NodeDatabaseRestoreListener> listeners = nodeDatabaseRestoreListeners.get(id);
		if(listeners!=null){
			for(NodeDatabaseRestoreListener listener:listeners){
				databaseCluster.addListener(listener);
			}
		}
	}

	@Override
	public void addListener(String id, DatabaseClusterListener listener) {
		addListener(databaseClusterListeners,id,listener);
	}

	@Override
	public void removeListener(String id, DatabaseClusterListener listener) {
		removeListener(databaseClusterListeners,id,listener);
	}

	@Override
	public void addSynchronizationListener(String id,
			SynchronizationListener listener) {

		addListener(synchronizationListeners,id,listener);
	}

	@Override
	public void removeSynchronizationListener(String id,
			SynchronizationListener listener) {
		removeListener(synchronizationListeners,id,listener);

	}

	@Override
	public void addListener(String id, NodeStateListener listener) {
		addListener(nodeStateListeners,id,listener);
	}

	public <T> void addListener(Map<String, Set<T>> map, String id, T listener) {
		Set<T> listeners = map.get(id);
		if(listeners==null){
			listeners = new LinkedHashSet<T>();
		}
		if(listener!=null){
			listeners.add(listener);
			map.put(id, listeners);
		}
	}

	@Override
	public void removeListener(String id, NodeStateListener listener) {
		removeListener(nodeStateListeners,id,listener);
	}

	private  <T> void removeListener(Map<String, Set<T>> map,String id, T listener) {
		Set<T> listeners = map.get(id);
		if(listeners!=null){
			if(listener!=null){
				listeners.remove(listener);
			}
			if(listeners.isEmpty()){
				map.remove(id);
			}
		}
	}

	@Override
	public void addListener(String id, NodeDatabaseRestoreListener listener){
		addListener(nodeDatabaseRestoreListeners,id,listener);
	}

	@Override
	public void removeListener(String id,NodeDatabaseRestoreListener listener){
		removeListener(nodeDatabaseRestoreListeners,id,listener);
	}
}
