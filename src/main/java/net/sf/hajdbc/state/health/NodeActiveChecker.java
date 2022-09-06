package net.sf.hajdbc.state.health;

import net.sf.hajdbc.DatabaseCluster;

public interface NodeActiveChecker {
  boolean isActive(DatabaseCluster databaseCluster);
}
