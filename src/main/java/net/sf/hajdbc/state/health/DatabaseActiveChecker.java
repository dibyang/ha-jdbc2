package net.sf.hajdbc.state.health;

import net.sf.hajdbc.DatabaseCluster;

public class DatabaseActiveChecker implements NodeActiveChecker {
  @Override
  public boolean isActive(DatabaseCluster databaseCluster) {
    if(databaseCluster!=null){
      return databaseCluster.getLocalDatabase().isActive();
    }
    return false;
  }
}
