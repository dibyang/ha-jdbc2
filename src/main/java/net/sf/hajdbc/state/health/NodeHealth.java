package net.sf.hajdbc.state.health;

import net.sf.hajdbc.state.distributed.NodeState;

public class NodeHealth {
  private NodeState state = NodeState.offline;
  private volatile long local;
  private volatile long arbiter;

  public NodeState getState() {
    return state;
  }

  public void setState(NodeState state) {
    this.state = state;
  }

  public boolean isValidArbiter() {
    return arbiter>0;
  }

  public boolean isValidLocal() {
    return isValidArbiter()&&(local>arbiter);
  }

  public long getLocal() {
    return local;
  }

  public void setLocal(long local) {
    this.local = local;
  }

  public long getArbiter() {
    return arbiter;
  }

  public void setArbiter(long arbiter) {
    this.arbiter = arbiter;
  }
}
