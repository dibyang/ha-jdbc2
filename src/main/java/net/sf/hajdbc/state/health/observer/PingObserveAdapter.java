package net.sf.hajdbc.state.health.observer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Observe adapter for ping
 */
public class PingObserveAdapter implements ObserveAdapter {

  public static final int TIME_OUT = 200;

  @Override
  public String getName() {
    return "ping";
  }

  @Override
  public int getWeight() {
    return 100;
  }

  @Override
  public boolean isObservable(String ip) {
    return isHostReachable(ip, TIME_OUT);
  }

  public boolean isHostReachable(String host, Integer timeOut) {
    try {
      return InetAddress.getByName(host).isReachable(timeOut);
    } catch (UnknownHostException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return false;
  }
}
