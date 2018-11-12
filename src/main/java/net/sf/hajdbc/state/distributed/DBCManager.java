package net.sf.hajdbc.state.distributed;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.distributed.CommandDispatcher;
import net.sf.hajdbc.distributed.Member;

import java.util.List;

public interface DBCManager<Z, D extends Database<Z>> {
  boolean isValid(String dbId);
  Member getMember(String ip);
  Member getLocal();
  CommandDispatcher<?> getDispatcher();
  void syncDbCfg();
}
