package net.sf.hajdbc.state;

import net.sf.hajdbc.distributed.Member;

public interface LeaderManager {
  boolean isLeader();
  long getTver();
  Member getLeader();
  //void leaderElection();
}
