package net.sf.hajdbc.state.health.observer;

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
  public boolean isObservable(String ip) {
    return false;
  }
}
