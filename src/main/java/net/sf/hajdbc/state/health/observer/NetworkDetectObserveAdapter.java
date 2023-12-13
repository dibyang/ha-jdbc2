package net.sf.hajdbc.state.health.observer;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Observe adapter for network delay detect
 */
public class NetworkDetectObserveAdapter implements ObserveAdapter {

  public static final int TIME_OUT = 70;
  public static final File DISABLE_FILE = Paths.get("/etc/ha-jdbc/net-delay-detect.disable").toFile();

  @Override
  public String getName() {
    return "net-delay-detect";
  }

  @Override
  public int getWeight() {
    return 80;
  }

  @Override
  public boolean isObservable(String localIp, List<String> ips) {
    if(DISABLE_FILE.exists()) {
      List<TcpLink> tcpLinks = TcpLink.readTcpLinks("/proc/net/tcp");
      Set<String> ips2 = tcpLinks.stream().filter(e -> e.getLocal().getIp().equals(localIp)
              && !e.getRemote().getIp().equals(localIp))
          .map(e -> e.getRemote().getIp())
          .limit(5)
          .collect(Collectors.toSet());

      if (!ips2.isEmpty()) {
        int success = 0;
        int fail = 0;
        for (String ip : ips2) {
          if (isHostReachable(ip, getTimeOut())) {
            success++;
          } else {
            fail++;
          }
        }
        return success >= fail;
      }
    }
    return true;
  }

  private int getTimeOut() {
    int timeout = TIME_OUT;
    String timeout_s = System.getProperty("net-delay-detect.timeout");
    try {
      timeout = Integer.parseInt(timeout_s);
    }catch (NumberFormatException e){
      //ignore e
    }
    return timeout;
  }

  private boolean isHostReachable(String host, int timeOut) {
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
