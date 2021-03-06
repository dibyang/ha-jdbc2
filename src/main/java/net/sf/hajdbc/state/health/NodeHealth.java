package net.sf.hajdbc.state.health;

import java.io.Serializable;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import net.sf.hajdbc.state.distributed.NodeState;

public class NodeHealth implements Serializable {
  private static final long serialVersionUID = 1L;

  private NodeState state = NodeState.offline;
  private final Set<String> activeDBs = new CopyOnWriteArraySet<>();
  private volatile long local;
  private volatile long arbiter;
  private volatile boolean lastOnlyHost = false;

  public Set<String> getActiveDBs() {
    return activeDBs;
  }

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
    return isValidArbiter()&&(local>=arbiter);
  }

  public boolean isEmpty() {
    return local==0&&arbiter==0;
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

  public boolean isLastOnlyHost() {
    return lastOnlyHost;
  }

  public void setLastOnlyHost(boolean lastOnlyHost) {
    this.lastOnlyHost = lastOnlyHost;
  }
}
