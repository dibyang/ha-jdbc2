package net.sf.hajdbc.state.sync;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.distributed.Member;

import java.io.File;

public interface SyncMgr {
  boolean upload(Database db, File file);
  boolean download(Database db, File file);
  Member getMember(Database db);
  boolean execute(Database db, Command cmd);
}
