package net.sf.hajdbc.state.distributed;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.sql.AbstractDatabase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SyncDBCfgCommand<Z, D extends Database<Z>> implements Command<D, StateCommandContext<Z, D>> {
  private D db=null;

  public D getDb() {
    return db;
  }

  public void setDb(D db) {
    this.db = db;
  }

  @Override
  public D execute(StateCommandContext<Z, D> context) {
    if(db!=null){
      ((AbstractDatabase)db).setLocal(false);
      context.getDatabaseCluster().addDatabase(db);
    }
    return context.getDatabaseCluster().getLocalDatabase();
  }
}
