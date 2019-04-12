package net.sf.hajdbc.state.health;

import net.sf.hajdbc.Database;

public interface NodeDatabaseRestoreListener<Z> {
  boolean beforeRestore(Database<Z> database);
  void afterRestored(Database<Z> database);
}
