package net.sf.hajdbc.state.distributed;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.state.LeaderToken;

public class InquireCommand<Z, D extends Database<Z>> implements Command<LeaderToken, StateCommandContext<Z, D>> {

  @Override
  public LeaderToken execute(StateCommandContext<Z, D> context) {
    LeaderToken token = context.getLeaderManager().getToken();
    return token;
  }
}
