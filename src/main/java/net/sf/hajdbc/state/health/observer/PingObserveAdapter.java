package net.sf.hajdbc.state.health.observer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

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
  public boolean isObservable(List<String> ips) {
    if(ips!=null) {
      for (String ip : ips) {
        return isHostReachable(ip, TIME_OUT);
      }
    }
    return false;
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
