package net.sf.hajdbc.state.health.observer;

import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.util.FileReader;
import net.sf.hajdbc.util.Tracer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Observe adapter for network delay detect
 */
public class NetworkDetectObserveAdapter implements ObserveAdapter {
  final Logger logger = LoggerFactory.getLogger(this.getClass());

  public static final int TIME_OUT = 60;

  private final FileReader<DetectMode> detectModeFileReader = FileReader.of2("net-delay-detect", s-> DetectMode.of(s));

  private final FileReader<Integer> detectTimeout = FileReader.of4int("net-delay-detect.timeout");

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
    DetectMode detectMode = detectModeFileReader
        .getData(DetectMode.all, DetectMode.disabled);
    if(!DetectMode.disabled.equals(detectMode)) {
      List<TcpLink> tcpLinks = TcpLink.readTcpLinks("/proc/net/tcp");
      Set<String> ips2 = tcpLinks.stream().filter(e -> e.getLocal().getIp().equals(localIp)
              && !e.getRemote().getIp().equals(localIp))
          .map(e -> e.getRemote().getIp())
          .limit(5)
          .collect(Collectors.toSet());

      List<String> remoteNodes = new ArrayList<>(ips);
      remoteNodes.removeIf(ip -> localIp.equals(ip));
      //配置节点通信状态
      boolean nodeOk = false;
      for (String remoteNode : remoteNodes) {
        if (isHostReachable(remoteNode, getTimeOut(needDown))) {
          nodeOk = true;
          break;
        }
      }
      ips2.removeIf(ip -> ips.contains(ip));

      //配置节点通信状态都异常,最小必需有1个可探测节点
      if (!nodeOk&&ips2.size() >= 1) {
        int success = 0;
        int fail = 0;
        for (String ip : ips2) {
          if (isHostReachable(ip, getTimeOut(needDown))) {
            success++;
          } else {
            fail++;
          }
        }
        if (Tracer.observe.isTrace()) {
          logger.log(Level.INFO, "success={0} fail={1}", success, fail);
        }
        if (DetectMode.half.equals(detectMode)) {
          return success >= fail;
        } else {
          return fail < ips2.size();
        }
      }
    }
    return true;
  }

  @Override
  public boolean isOptional() {
    return false;
  }

  private int getTimeOut(boolean needDown) {
    int timeout = detectTimeout
        .getData(TIME_OUT);
    if(timeout<20){
      timeout = 20;
    }
    if(timeout>100){
      timeout = 100;
    }
    if(!needDown){
      timeout-=15;
    }
    return timeout;
  }

  private boolean isHostReachable(String host, int timeOut) {
    boolean reachable = false;
    try {
      reachable = InetAddress.getByName(host).isReachable(timeOut);
    } catch (UnknownHostException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
    if (Tracer.observe.isTrace()) {
      if(reachable){
        logger.log(Level.INFO, "detect delay {0} success.", host);
      }else{
        logger.log(Level.INFO, "detect delay {0} fail.", host);
      }
    }
    return reachable;
  }
}
