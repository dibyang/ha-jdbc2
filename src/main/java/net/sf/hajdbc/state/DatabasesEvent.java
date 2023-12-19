package net.sf.hajdbc.state;

import net.sf.hajdbc.util.Event;

import java.util.Set;

public class DatabasesEvent extends Event<Set<String>>
{
	private static final long serialVersionUID = -6709361835865578668L;
	/**
	 * @param databases
	 */
	public DatabasesEvent(Set<String> databases)
	{
		super(databases);
	}
}
