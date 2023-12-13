package net.sf.hajdbc.distributed.jgroups;

import net.sf.hajdbc.distributed.HaExt;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.util.Preconditions;
import org.jgroups.*;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.jgroups.stack.ProtocolStack;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class HaChannel extends Channel implements HaExt {
  private final Logger logger = LoggerFactory.getLogger(this.getClass());
  private final List<String> ips = new ArrayList<>();
  private final ProtocolStackConfigurator configurator;
  private String activeIp = null;
  private JChannel activeChannel = null;
  private String name;

  public HaChannel(List<String> ips, ProtocolStackConfigurator configurator) throws Exception {
    Preconditions.checkNotNull(ips);
    Preconditions.checkArgument(!ips.isEmpty());
    this.configurator = configurator;
    this.ips.addAll(ips);
    activeIp = findIp();
    if(activeIp!=null){
      System.setProperty("jgroups.bind_addr", activeIp);
      activeChannel = new JChannel(configurator);
      activeChannel.setName(activeIp);
    }
  }

  public void detectNetwork(){
    List<IpDelay> ipDelays = getIpDelays().stream()
        .sorted().collect(Collectors.toList());
    IpDelay first = ipDelays.stream().findFirst().orElse(null);
    if(first!=null&&!first.getIp().equals(activeIp)) {
      IpDelay active = ipDelays.stream().filter(e -> e.getIp().equals(activeIp))
          .findFirst().orElse(null);
      if(active!=null){
        if((active.getDelay()- first.getDelay())>100){
          logger.log(Level.INFO,"channel change {0} delay {1} to {2} delay {3}");
          System.setProperty("jgroups.bind_addr", first.getIp());
          try {
            activeIp = first.getIp();
            JChannel channel = new JChannel(configurator);
            channel.setDiscardOwnMessages(false);
            channel.setName(activeIp);
            // Connect and fetch state
            int failCount = 0;
            boolean connected = false;
            while(!connected) {
              try {
                channel.connect(activeChannel.getClusterName(), null, 2000);
                connected = true;
              }catch (Exception e){
                failCount++;
                if(failCount>=5){
                  throw e;
                }
              }
            }
            synchronized (this) {
              activeChannel.close();
              activeChannel = channel;
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      }
    }
  }

  @Override
  public String getActiveIp() {
    return activeIp;
  }

  List<IpDelay> getIpDelays(){
    synchronized (ips) {
      List<IpDelay> list = new ArrayList<>();
      for (String ip : ips) {
        IpDelay ipDelay = new IpDelay(ip);
        long delay = detect(ip);
        ipDelay.setDelay(delay);
        list.add(ipDelay);
      }
      return list;
    }
  }

  private String findIp(){
    return getIpDelays().stream().sorted().findFirst()
        .map(e->e.getIp()).orElse(null);
  }

  private long detect(String ip){
    long delay = Long.MAX_VALUE;
    try {
      InetAddress address = InetAddress.getByName(ip);
      long startTime = System.nanoTime();
      if (address.isReachable(300)) { // 设置超时时间为300毫秒
        long endTime = System.nanoTime();
        delay = (endTime - startTime) / 1000000; // 转换成毫秒
      } else {
        delay = Long.MAX_VALUE;
      }
    } catch (Exception e) {
      delay = Long.MAX_VALUE;
    }
    return delay;
  }

  @Override
  public synchronized ProtocolStack getProtocolStack() {
    return activeChannel.getProtocolStack();
  }

  @Override
  public synchronized void connect(String cluster_name) throws Exception {
    activeChannel.connect(cluster_name);
  }

  @Override
  public synchronized void connect(String cluster_name, Address target, long timeout) throws Exception {
    activeChannel.connect(cluster_name, target, timeout);
  }

  @Override
  public synchronized void disconnect() {
    activeChannel.disconnect();
  }

  @Override
  public synchronized void close() {
    activeChannel.close();
  }

  @Override
  public synchronized Map<String, Object> dumpStats() {
    return activeChannel.dumpStats();
  }

  @Override
  public synchronized void send(Message msg) throws Exception {
    activeChannel.send(msg);
  }

  @Override
  public synchronized void send(Address dst, Object obj) throws Exception {
    activeChannel.send(dst, obj);
  }

  @Override
  public synchronized void send(Address dst, byte[] buf) throws Exception {
    activeChannel.send(dst, buf);
  }

  @Override
  public synchronized void send(Address dst, byte[] buf, int offset, int length) throws Exception {
    activeChannel.send(dst, buf, offset, length);
  }

  @Override
  public synchronized View getView() {
    return activeChannel.getView();
  }

  @Override
  public synchronized Address getAddress() {
    return activeChannel.getAddress();
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public synchronized String getName(Address member) {
    return activeChannel.getName(member);
  }

  @Override
  public synchronized void setName(String name) {
    this.name = name;
  }

  @Override
  public Channel name(String name) {
    this.setName(name);
    return this;
  }

  @Override
  public synchronized String getClusterName() {
    return activeChannel.getClusterName();
  }

  @Override
  public synchronized boolean flushSupported() {
    return activeChannel.flushSupported();
  }

  @Override
  public synchronized void startFlush(List<Address> flushParticipants, boolean automatic_resume) throws Exception {
    activeChannel.startFlush(flushParticipants, automatic_resume);
  }

  @Override
  public synchronized void startFlush(boolean automatic_resume) throws Exception {
    activeChannel.startFlush(automatic_resume);
  }

  @Override
  public synchronized void stopFlush() {
    activeChannel.stopFlush();
  }

  @Override
  public synchronized void stopFlush(List<Address> flushParticipants) {
    activeChannel.stopFlush(flushParticipants);
  }

  @Override
  public synchronized void getState(Address target, long timeout) throws Exception {
    activeChannel.getState(target, timeout);
  }
}
