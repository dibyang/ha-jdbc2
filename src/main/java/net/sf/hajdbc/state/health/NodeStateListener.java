package net.sf.hajdbc.state.health;

import net.sf.hajdbc.state.distributed.NodeState;

public interface NodeStateListener {
  void changeState(NodeState oldState,NodeState newState);
}
