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
package net.sf.hajdbc.logging;

import java.text.MessageFormat;

/**
 * Abstract logger implementation.
 * @author Paul Ferraro
 */
public abstract class AbstractLogger implements Logger
{
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.logging.Logger#log(net.sf.hajdbc.logging.Level, java.lang.String, java.lang.Object[])
	 */
	@Override
	public final void log(Level level, String pattern, Object... args)
	{
		this.log(level, null, pattern, args);
	}
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.logging.Logger#log(net.sf.hajdbc.logging.Level, java.lang.Throwable)
	 */
	@Override
	public final void log(Level level, Throwable e)
	{
		this.log(level, e, e.getMessage());
	}

	protected static String format(String pattern, Object... args)
	{
		return (args.length == 0) ? pattern : MessageFormat.format(pattern, args);
	}
}
