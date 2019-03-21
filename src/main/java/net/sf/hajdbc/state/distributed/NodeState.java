package net.sf.hajdbc.state.distributed;


public enum NodeState {
  offline,
  host,
  ready,
  backup;
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
