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
package net.sf.hajdbc.util;

import java.util.concurrent.TimeUnit;

/**
 * Simple struct to represent a time period.
 * @author Paul Ferraro
 */
public final class TimePeriod
{
	private final long value;
	private final TimeUnit unit;

	public TimePeriod(long value, TimeUnit unit)
	{
		this.value = value;
		this.unit = unit;
	}

	public long getValue()
	{
		return this.value;
	}

	public TimeUnit getUnit()
	{
		return this.unit;
	}
}
