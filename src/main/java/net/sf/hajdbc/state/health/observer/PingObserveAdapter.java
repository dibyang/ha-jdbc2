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
  public boolean isOptional() {
    return true;
  }

  @Override
  public int getWeight() {
    return 100;
  }

  @Override
  public boolean isObservable(boolean needDown, String localIp, List<String> ips) {
    if(ips!=null&&!ips.isEmpty()) {
      int success = 0;
      int fail = 0;
      for (String ip : ips) {
        if(isHostReachable(ip, TIME_OUT)){
          success++;
        }else{
          fail++;
        }
      }
      return success>fail;
    }
    return true;
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
