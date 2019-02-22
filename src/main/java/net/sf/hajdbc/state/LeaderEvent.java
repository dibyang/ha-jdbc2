package net.sf.hajdbc.state;

import net.sf.hajdbc.util.Event;

public class LeaderEvent extends Event<LeaderToken> {
  public LeaderEvent(LeaderToken source) {
    super(source);
  }
}
