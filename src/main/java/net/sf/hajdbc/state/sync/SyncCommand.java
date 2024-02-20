package net.sf.hajdbc.state.sync;

import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.state.distributed.StateCommandContext;

public interface SyncCommand<R> extends Command<R, StateCommandContext> {
}
