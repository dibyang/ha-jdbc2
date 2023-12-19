package net.sf.hajdbc.state.distributed;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.state.DatabasesEvent;

import java.util.HashSet;

public class SyncActiveDbsCommand<Z, D extends Database<Z>> implements Command<Boolean, StateCommandContext<Z, D>>
{
	private final DatabasesEvent event;

	public SyncActiveDbsCommand(DatabasesEvent event)
	{
		this.event = event;
	}



	@Override
	public Boolean execute(StateCommandContext<Z, D> context) {
		if(event!=null&&event.getSource()!=null&&event.getSource().size()>0){
			context.getHealth().checkActiveDatabases(new HashSet<>(event.getSource()));
		}
		return true;
	}
}
