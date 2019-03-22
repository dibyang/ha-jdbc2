package net.sf.hajdbc.state.distributed;


public enum NodeState {
  offline,
  host(true),
  ready,
  backup(true);
  private boolean canUpdate = false;

  public boolean isCanUpdate() {
    return canUpdate;
  }

  NodeState() {
    this(false);
  }
  NodeState(boolean canUpdate) {
    this.canUpdate = canUpdate;
  }

  public static NodeState of(String name){
    NodeState state = NodeState.offline;
    NodeState[] values = NodeState.values();
    for(NodeState e:values){
      if(e.name().equals(name)){
        state = e ;
        break;
      }
    }
    return state;
  }
  public static NodeState of(int ordinal){
    NodeState state = NodeState.offline;
    NodeState[] values = NodeState.values();
    for(NodeState e:values){
      if(e.ordinal()==ordinal){
        state = e ;
        break;
      }
    }
    return state;
  }
}
