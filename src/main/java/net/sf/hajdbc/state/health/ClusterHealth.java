package net.sf.hajdbc.state.health;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.state.distributed.NodeState;

public interface ClusterHealth {

  void start();

  void stop();

  NodeHealth getNodeHealth();

  NodeHealth getNodeHealth(Member member);

  void receiveHeartbeat();

  NodeState getState();

  boolean isHost();

  void setState(NodeState state);

  void incrementToken();

  void updateToken(long token);

  void host(Member host, long token);
}
