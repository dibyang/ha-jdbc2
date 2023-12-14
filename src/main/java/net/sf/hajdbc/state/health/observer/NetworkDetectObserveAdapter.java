package net.sf.hajdbc.state.health.observer;

import net.sf.hajdbc.state.health.FileReader;

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

  public static final int TIME_OUT = 60;

  private final FileReader<DetectMode> net_delay_detect_reader = FileReader.of2("/etc/ha-jdbc/net-delay-detect", s->DetectMode.of(s));

  @Override
  public String getName() {
    return "net-delay-detect";
  }

  @Override
  public int getWeight() {
    return 80;
  }

  @Override
  public boolean isObservable(boolean needDown, String localIp, List<String> ips) {
    DetectMode net_delay_detect = net_delay_detect_reader
        .getData(DetectMode.disabled)
        .orElse(DetectMode.all);
    if(!DetectMode.disabled.equals(net_delay_detect)) {
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
          if (isHostReachable(ip, getTimeOut(needDown))) {
            success++;
          } else {
            fail++;
          }
        }
        if(DetectMode.half.equals(net_delay_detect)){
          return success >= fail;
        }else {
          return fail==ips2.size();
        }
      }
    }
    return true;
  }

  private int getTimeOut(boolean needDown) {
    int timeout = TIME_OUT;
    String timeout_s = System.getProperty("net-delay-detect.timeout");
    try {
      timeout = Integer.parseInt(timeout_s);
    }catch (NumberFormatException e){
      //ignore e
    }
    if(!needDown){
      timeout-=15;
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
