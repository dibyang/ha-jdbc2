/*
 * HA-JDBC: High-Availability JDBC
 * Copyright (C) 2013  Paul Ferraro
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

import net.sf.hajdbc.Database;

/**
 * 
 * @author Paul Ferraro
 */
public interface ChildProxyFactory<Z, D extends Database<Z>, P, PE extends Exception, T, E extends Exception> extends ProxyFactory<Z, D, T, E>
{
	ProxyFactory<Z, D, P, PE> getParent();
	
	P getParentProxy();

	void remove();
	
	void close(D database);

	void close(D database, T object);
}
