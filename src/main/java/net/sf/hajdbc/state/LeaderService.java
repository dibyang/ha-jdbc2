package net.sf.hajdbc.state;


import net.sf.hajdbc.distributed.Member;

/**
 *
 * Lerder manage service interface
 *
 * @author dib
 */
public interface LeaderService {
  boolean isLeader(Member member);
  void removed(Member member);
  void leader(Member leader,long tver);
  LeaderToken getToken();
  boolean hasLeader();
  //long getTver();
  //Member getLeader();
  //void leaderElection();
}
