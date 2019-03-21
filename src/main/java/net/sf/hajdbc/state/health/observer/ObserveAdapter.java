package net.sf.hajdbc.state.health.observer;

/**
 * Observe adapter
 */
public interface ObserveAdapter {

  /**
   * Returns adapter name
   * @return adapter name
   */
  String  getName();

  /**
   * Returns adapter weight
   * Higher value is first running
   * @return adapter weight
   */
  int getWeight();

  /**
   * Return observable or not
   * @param ip ip address
   * @return observable or not
   */
  boolean isObservable(String ip);
}
