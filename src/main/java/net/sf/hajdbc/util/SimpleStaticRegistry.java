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
package net.sf.hajdbc.util;

import java.util.Map;

/**
 * @author Paul Ferraro
 */
public class SimpleStaticRegistry<K, V> implements StaticRegistry<K, V>
{
	private final ExceptionMessageFactory<K> factory;
	private final Map<K, V> map;
	
	public SimpleStaticRegistry(Map<K, V> map)
	{
		this(map, null);
	}
	
	public SimpleStaticRegistry(Map<K, V> map, ExceptionMessageFactory<K> factory)
	{
		this.map = map;
		this.factory = factory;
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.util.StaticRegistry#get(java.lang.Object)
	 */
	@Override
	public V get(K key)
	{
		V value = this.map.get(key);
		if ((value == null) && (this.factory != null))
		{
			throw new IllegalArgumentException(this.factory.createMessage(key));
		}
		return value;
	}
	
	public interface ExceptionMessageFactory<K>
	{
		String createMessage(K key);
	}
}
