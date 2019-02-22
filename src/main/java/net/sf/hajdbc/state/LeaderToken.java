package net.sf.hajdbc.state;

import net.sf.hajdbc.distributed.Member;

import java.io.Serializable;

/**
 * leader token
 * @author dib
 */
public class LeaderToken implements Serializable {

  private static final long serialVersionUID = -83397110197926495L;

  private volatile Member leader = null;
  private volatile long tver = 0;


  public Member getLeader() {
    return leader;
  }

  public void setLeader(Member leader) {
    this.leader = leader;
  }

  public boolean hasLeader(){
    return leader!=null;
  }

  public long getTver() {
    return tver;
  }

  public void setTver(long tver) {
    this.tver = tver;
  }

  public boolean update(LeaderToken token){
    if(token.leader==null||token.tver>=tver) {
      this.setLeader(token.leader);
      this.setTver(token.tver);
      return true;
    }
    return false;
  }

  public LeaderToken copy(){
    LeaderToken copy = new LeaderToken();
    copy.update(this);
    return copy;
  }

}
