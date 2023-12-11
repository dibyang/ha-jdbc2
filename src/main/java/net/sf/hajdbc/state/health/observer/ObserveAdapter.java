package net.sf.hajdbc.state.health.observer;

import java.util.List;

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
   * @param localIp local ip
   * @param ips ip list
   * @return observable or not
   */
  boolean isObservable(String localIp, List<String> ips);
}
