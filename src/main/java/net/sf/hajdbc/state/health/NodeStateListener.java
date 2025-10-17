package net.sf.hajdbc.state.health;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.state.distributed.NodeState;

public interface NodeStateListener {
  void changeState(NodeState oldState,NodeState newState);
  /**
   * 数据库非活动时调用
   */
  default <D extends Database<?>> void notActiveDatabase(D localDb){
    //do nothing
  }
}
