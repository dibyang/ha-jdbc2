package net.sf.hajdbc.state.health.observer;

import java.util.List;

public class Test1ObserveAdapter implements ObserveAdapter {

  @Override
  public String getName() {
    return "test1";
  }

  @Override
  public int getWeight() {
    return 10;
  }

  @Override
  public boolean isObservable(boolean needDown, String localIp, List<String> ips) {
    return false;
  }
}
