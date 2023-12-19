package net.sf.hajdbc.state.health;

import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.distributed.MembershipListener;
import net.sf.hajdbc.distributed.Stateful;
import net.sf.hajdbc.state.distributed.NodeState;

import java.util.Set;

public interface ClusterHealth extends MembershipListener {

  void start();

  void stop();

  NodeHealth getNodeHealth();

  NodeHealth getNodeHealth(Member member);

  void receiveHeartbeat(long sendTime);

  long getOffsetTime();

  long getHostTime();

  boolean canWrite();

  NodeState getState();

  boolean isHost();

  void setState(NodeState state);

  void incrementToken();

  void updateToken(long token);

  void host(Member host, long token);

  Member getHost();

  long getMaxElectTime();

  public void checkActiveDatabases(Set<String> activeDatabases);
}
