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

import java.util.concurrent.ExecutorService;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlEnumValue;

import net.sf.hajdbc.TransactionMode;
import net.sf.hajdbc.util.concurrent.SynchronousExecutor;

/**
 * @author Paul Ferraro
 *
 */
@XmlEnum(String.class)
public enum TransactionModeEnum implements TransactionMode
{
	@XmlEnumValue("parallel")
	PARALLEL(false),
	@XmlEnumValue("serial")
	SERIAL(true);

	private final boolean synchronous;
	
	private TransactionModeEnum(boolean synchronous)
	{
		this.synchronous = synchronous;
	}
	
	@Override
	public ExecutorService getTransactionExecutor(ExecutorService executor, boolean end)
	{
		return this.synchronous ? new SynchronousExecutor(executor, end) : executor;
	}
}
