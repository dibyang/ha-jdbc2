package net.sf.hajdbc.state.health.observer;

import net.sf.hajdbc.util.FileReader;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Observe adapter for connect
 */
public abstract class ConnectObserveAdapter implements ObserveAdapter {

  public static final int TIME_OUT = 500;

  private final FileReader<DetectMode> observeFileReader = FileReader.of2("connect-observe", s-> DetectMode.of(s));


  public abstract int getPort();

  @Override
  public boolean isOptional() {
    return true;
  }

  @Override
  public boolean isObservable(boolean needDown, String localIp, List<String> ips) {
    DetectMode detectMode = observeFileReader.getData(DetectMode.disabled);
    if(!DetectMode.disabled.equals(detectMode)) {
      if (ips != null && !ips.isEmpty()) {
        int success = 0;
        int fail = 0;
        for (String ip : ips) {
          if (isConnectable(ip)) {
            success++;
          } else {
            fail++;
          }
        }
        return success > fail;
      }
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
