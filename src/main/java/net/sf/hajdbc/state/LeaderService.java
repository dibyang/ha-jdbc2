package net.sf.hajdbc.state;


import net.sf.hajdbc.distributed.Member;

/**
 *
 * Lerder manage service interface
 *
 * @author dib
 */
public interface LeaderService {
  boolean isLeader(String member);
  void removed(String member);
  void leader(String leader,long tver);
  LeaderToken getToken();
  boolean hasLeader();
  //long getTver();
  //Member getLeader();
  //void leaderElection();
}
