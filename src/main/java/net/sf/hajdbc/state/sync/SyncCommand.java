package net.sf.hajdbc.state.sync;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.state.distributed.StateCommandContext;

public interface SyncCommand<Z, D extends Database<Z>> extends Command<Boolean, StateCommandContext<Z, D>> {
}
