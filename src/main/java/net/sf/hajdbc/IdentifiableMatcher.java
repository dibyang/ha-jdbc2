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

import net.sf.hajdbc.util.Matcher;

public class IdentifiableMatcher<T extends Identifiable> implements Matcher<T>
{
	private final String id;

	public IdentifiableMatcher(String id)
	{
		this.id = id;
	}

	@Override
	public boolean matches(T service)
	{
		return (this.id != null) && (service != null) ? this.id.equalsIgnoreCase(service.getId()) : (this.id == service.getId());
	}

	@Override
	public String toString()
	{
		return this.id;
	}
}
