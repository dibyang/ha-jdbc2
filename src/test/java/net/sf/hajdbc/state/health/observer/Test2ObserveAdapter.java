package net.sf.hajdbc.state.health.observer;

public class Test2ObserveAdapter implements ObserveAdapter {

  @Override
  public String getName() {
    return "test2";
  }

  @Override
  public int getWeight() {
    return 20;
  }

  @Override
  public boolean isObservable(String ip) {
    return false;
  }
}
