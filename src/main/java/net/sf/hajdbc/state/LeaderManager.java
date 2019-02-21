package net.sf.hajdbc.state;

import net.sf.hajdbc.distributed.Member;

import java.net.NetworkInterface;
import java.net.SocketException;

/**
 * Lerder manager
 * @author dib
 */
public class LeaderManager implements LeaderService {

  private final NetworkInterface nic;
  private final Member local;
  private LeaderTokenStore leaderTokenStore = new LocalLeaderTokenStore();

  public LeaderManager(Member local, NetworkInterface nic) {
    this.local = local;
    this.nic = nic;
  }


  public boolean isEnabled(){
    return nic != null;
  }

  public boolean isUp(){
    if(isEnabled()) {
      try {
        return nic.isUp();
      } catch (SocketException e) {
        e.printStackTrace();
      }
    }
    return false;
  }

  public LeaderToken getToken() {
    LeaderToken token = leaderTokenStore.getToken();
    if(isUp()){
      if(!token.hasLeader()){
        token.setTver(1);
        token.setLeader(local);
      }
    }else{
      token.setLeader(null);
    }
    return token;
  }

  @Override
  public boolean hasLeader() {
    return leaderTokenStore.getToken().hasLeader();
  }

  @Override
  public boolean isLeader(Member member) {
    LeaderToken token = this.getToken();
    Member leader = token.getLeader();
    if(leader!=null) {
      return leader.equals(member);
    }
    return false;
  }

  public void removed(Member member)
  {
    Member leader = leaderTokenStore.getToken().getLeader();
    if(leader!=null) {
      if(leader.equals(member)){
        leaderTokenStore.getToken().setLeader(null);
        leaderTokenStore.save();
      }
    }
  }

  @Override
  public void leader(Member leader, long tver) {
    if(leader!=null&&tver>leaderTokenStore.getToken().getTver()){
      leaderTokenStore.getToken().setTver(tver);
      leaderTokenStore.getToken().setLeader(leader);
      leaderTokenStore.save();
    }
  }
}
