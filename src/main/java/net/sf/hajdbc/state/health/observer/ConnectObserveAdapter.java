package net.sf.hajdbc.state.health.observer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Observe adapter for connect
 */
public abstract class ConnectObserveAdapter implements ObserveAdapter {

  public static final int TIME_OUT = 500;

  public abstract int getPort();

  @Override
  public boolean isObservable(String localIp, List<String> ips) {
    if(ips!=null&&!ips.isEmpty()) {
      for (String ip : ips) {
        return isConnectable(ip);
      }
      return false;
    }
    return true;
  }

  public  boolean isConnectable(String host) {
    Socket socket = new Socket();
    try {
      socket.connect(new InetSocketAddress(host, getPort()), TIME_OUT);
    } catch (IOException e) {
      //ignore io exception
      return false;
    } finally {
      try {
        socket.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    return true;
  }
}
