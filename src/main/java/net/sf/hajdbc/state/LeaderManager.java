package net.sf.hajdbc.state;

import net.sf.hajdbc.distributed.Member;

import java.net.NetworkInterface;
import java.net.SocketException;

/**
 * Lerder manager
 * @author dib
 */
public class LeaderManager implements LeaderService {

  private NetworkInterface nic;
  private String local;
  private LeaderTokenStore leaderTokenStore = new LocalLeaderTokenStore();
  private volatile boolean election = false;

  public LeaderManager() {

  }

  public boolean isElection() {
    return election;
  }

  public void elect() {
    this.election = true;
  }

  public boolean isInited(){
    return local != null;
  }

  public void init(String local, NetworkInterface nic){
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
      if(!token.hasLeader()&&token.getTver()<1){
        token.setLeader(local);
      }
    }else{
      token.setLeader(null);
    }
    return token;
  }

  @Override
  public synchronized boolean hasLeader() throws InterruptedException {
    while(election){
      wait();
    }
    return leaderTokenStore.getToken().hasLeader();
  }

  @Override
  public synchronized boolean isLeader(String member) throws InterruptedException {
    while(election){
      wait();
    }
    LeaderToken token = this.getToken();
    String leader = token.getLeader();
    if(leader!=null) {
      return leader.equals(member);
    }
    return false;
  }

  public void removed(String member)
  {
    LeaderToken token = leaderTokenStore.getToken();
    String leader = token.getLeader();
    if(leader!=null) {
      if(leader.equals(member)){
        token.setLeader(null);
        leaderTokenStore.update(token);
        leaderTokenStore.save();
      }
    }
  }

  @Override
  public synchronized void leader(String leader, long tver) {

    LeaderToken token = leaderTokenStore.getToken();
    if(leader!=null&&tver> token.getTver()){
      token.setLeader(leader);
      if(leader!=null){
        token.setTver(tver);
      }
      leaderTokenStore.update(token);
      leaderTokenStore.save();
    }
    if(!election){
      return;
    }
    election = false;
    notifyAll();

  }
}
