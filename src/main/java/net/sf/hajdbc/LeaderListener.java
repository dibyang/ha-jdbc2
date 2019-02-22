package net.sf.hajdbc;

import net.sf.hajdbc.state.LeaderEvent;

import java.util.EventListener;

public interface LeaderListener extends EventListener {
  void leader(LeaderEvent event);
}
