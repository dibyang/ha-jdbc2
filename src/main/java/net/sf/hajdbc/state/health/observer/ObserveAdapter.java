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
   * @param needDown 是否是下线检测，false表示上线检测
   * @param localIp local ip
   * @param ips ip list
   * @return observable or not
   */
  boolean isObservable(boolean needDown, String localIp, List<String> ips);


  /**
   * 是否是可选观察者
   * @return
   */
  boolean isOptional();
}
