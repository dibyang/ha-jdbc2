package net.sf.hajdbc.state.distributed;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.state.LeaderToken;

public class LeaderCommand<Z, D extends Database<Z>> implements Command<Boolean, StateCommandContext<Z, D>> {

  private final LeaderToken token;

  public LeaderCommand(LeaderToken token) {
    this.token = token;
  }

  @Override
  public Boolean execute(StateCommandContext<Z, D> context) {
    if(token!=null){
      return context.leader(token.getLeader(),token.getTver());
    }
    return false;
  }
}
