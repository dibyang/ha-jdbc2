package net.sf.hajdbc.state.health;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.sf.hajdbc.Database;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.state.DatabaseEvent;
import net.sf.hajdbc.state.distributed.DistributedStateManager;
import net.sf.hajdbc.state.distributed.NodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusterHealth<Z, D extends Database<Z>> implements Runnable{
  private final static Logger logger = LoggerFactory.getLogger(ClusterHealth.class);

  public static final int HEARTBEAT_LOST_MAX = 3;
  private long maxElectTime = 4 * 60*1000L;
  private DistributedStateManager<Z, D> stateManager;
  private final Arbiter arbiter;
  private volatile boolean unattended = true;

  private NodeState state = NodeState.offline;
  private final AtomicInteger counter = new AtomicInteger(0);
  private volatile long lastHeartbeat = 0;

  HeartBeatCommand beatCommand = new HeartBeatCommand();
  NodeHealthCommand healthCommand = new NodeHealthCommand();

  private final ScheduledExecutorService scheduledService = Executors.newScheduledThreadPool(1, new ThreadFactory() {
    private AtomicInteger atoInteger = new AtomicInteger(0);
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r);
      t.setName("cluster-health-Thread "+ atoInteger.getAndIncrement());
      return t;
    }
  });


  public ClusterHealth(DistributedStateManager<Z, D> stateManager) {
    this.stateManager = stateManager;
    stateManager.setExtContext(this);
    arbiter = new Arbiter("/datapool/.sconf/");
  }

  public void start(){
    elect();
    if(state.equals(NodeState.offline)){
      throw new RuntimeException("not find host node.");
    }
    scheduledService.scheduleWithFixedDelay(this,0,2000, TimeUnit.MILLISECONDS);
  }
  public void stop(){
    scheduledService.shutdown();
  }

  public NodeHealth getNodeHealth(){
    NodeHealth health = new NodeHealth();
    health.setState(state);
    health.setLocal(arbiter.getLocal().getToken());
    health.setArbiter(arbiter.getArbiter().getToken());
    return health;
  }


  /**
   * Receive host node heart beat.
   */
  public void receiveHeartbeat(){
    counter.set(0);
    lastHeartbeat = System.currentTimeMillis();
  }

  public NodeState getState() {
    return state;
  }

  public boolean isHost(){
    return NodeState.host.equals(state);
  }

  public void setState(NodeState state) {
    if(state==null){
      state = NodeState.offline;
    }
    if(!state.equals(this.state)){
      NodeState old  = this.state;
      this.state = state;
      changeState(old,this.state);
    }
  }

  void changeState(NodeState oldState,NodeState newState){
    logger.info("node state from "+oldState+" to "+newState);
    stateManager.getDatabaseCluster().changeState(oldState,newState);
  }

  UpdateTokenCommand updateTokenCommand = new UpdateTokenCommand();

  public void incrementToken(){
    long token = arbiter.getLocal().getToken() + 1;
    updateTokenCommand.setToken(token);
    stateManager.executeAll(updateTokenCommand);
  }

  public void updateToken(long token){
    if(state.isCanUpdate()){
      arbiter.update(token);
    }
  }

  /**
   * Send heartbeat.
   */
  private void sendHeartbeat(){
    stateManager.executeAll(beatCommand);
  }



  /**
   * Does it lost heart beat.
   * @return Does it lost heart beat
   */
  private boolean isLostHeartBeat(){
    int count = counter.incrementAndGet();
    if(count>= HEARTBEAT_LOST_MAX){
      counter.set(0);
      return true;
    }
    return false;
  }

  /**
   * Returns can it elect.
   * @return Can it elect.
   */
  private boolean canElect(){
    if(isUp()){
      if(arbiter.isObservable()){
        return true;
      }
    }
    return false;
  }

  /**
   * start elect host node.
   */
  private synchronized void elect(){
    logger.info("host elect begin.");
    long beginElectTime = System.currentTimeMillis();
    Entry<Member, NodeHealth> host = doElect(beginElectTime);
    while(unattended&&host==null){
      host = doElect(beginElectTime);
    }
    if(host!=null){
      HostCommand hostCommand = new HostCommand();
      hostCommand.setHost(host.getKey());
      hostCommand.setToken(host.getValue().getLocal());
      stateManager.executeAll(hostCommand);
    }
    logger.info("host elect end.");
  }

  private Entry<Member, NodeHealth> doElect(long beginElectTime) {
    Map<Member, NodeHealth> all = stateManager.executeAll(healthCommand);

    //delete invalid data.
    Iterator<Entry<Member, NodeHealth>> iterator = all.entrySet().iterator();
    while(iterator.hasNext()){
      Entry<Member, NodeHealth> next = iterator.next();
      if(next.getValue()==null){
        iterator.remove();
      }
    }
    Entry<Member, NodeHealth> host = null;
    //find host
    host = findNodeByState(all, NodeState.host);
    //not find host. find backup
    if(host==null){
      host = findNodeByState(all, NodeState.backup);
    }

    //not find backup. find by valid local
    if(host==null){
      host = findNodeByValidLocal(all);
    }

    //not find valid local. find empty node
    if(host==null){
      host = findNodeByEmpty(all);
    }
    if(host==null){
      long time = System.currentTimeMillis() - beginElectTime;
      if(time > maxElectTime){
        host = findNodeByToken(all);
      }
    }

    return host;

  }

  public void host(Member host,long token){
    if(host!=null){
      if(stateManager.getLocal().equals(host)){
        setState(NodeState.host);
        stateManager.activated(new DatabaseEvent(stateManager.getDatabaseCluster().getLocalDatabase()));
      }else{
        if(token>=arbiter.getLocal().getToken()) {
          setState(NodeState.ready);
        }
      }
    }else{
      setState(NodeState.offline);
    }

  }

  private Entry<Member, NodeHealth> findNodeByToken(Map<Member, NodeHealth> all) {
    Entry<Member, NodeHealth> find = null;
    Iterator<Entry<Member, NodeHealth>> iterator = all.entrySet().iterator();
    while(iterator.hasNext()) {
      Entry<Member, NodeHealth> next = iterator.next();
      NodeHealth health = next.getValue();
      if (health != null) {
        if(find==null||health.getLocal()>find.getValue().getLocal()){
          find = next;
        }
      }
    }
    return find;
  }

  private Entry<Member, NodeHealth> findNodeByEmpty(Map<Member, NodeHealth> all) {
    Entry<Member, NodeHealth> find = null;
    Iterator<Entry<Member, NodeHealth>> iterator = all.entrySet().iterator();
    while(iterator.hasNext()) {
      Entry<Member, NodeHealth> next = iterator.next();
      NodeHealth health = next.getValue();
      if (health != null && health.isEmpty()) {
        find = next;
        break;
      }
    }
    return find;
  }

  private Entry<Member, NodeHealth> findNodeByValidLocal(Map<Member, NodeHealth> all) {
    Entry<Member, NodeHealth> find = null;
    Iterator<Entry<Member, NodeHealth>> iterator = all.entrySet().iterator();
    while(iterator.hasNext()) {
      Entry<Member, NodeHealth> next = iterator.next();
      NodeHealth health = next.getValue();
      if (health != null && health.isValidLocal()) {
        find = next;
        break;
      }
    }
    return find;
  }

  private Entry<Member, NodeHealth> findNodeByState(Map<Member, NodeHealth> all,NodeState state) {
    Entry<Member, NodeHealth> find = null;
    Iterator<Entry<Member, NodeHealth>> iterator = all.entrySet().iterator();
    while(iterator.hasNext()) {
      Entry<Member, NodeHealth> next = iterator.next();
      NodeHealth health = next.getValue();
      if (health != null && health.getState().equals(find)) {
        find = next;
        break;
      }
    }
    return find;
  }

  /**
   * Does it need to be down.
   * @return Does it need to be down?
   */
  private boolean isNeedDown(){
    if(!isUp()||!arbiter.isObservable()){
      return true;
    }
    return false;
  }

  private void downNode(){
    setState(NodeState.offline);
  }


  @Override
  public synchronized void run() {
    try {
      if(NodeState.host.equals(state)){
        if(isNeedDown()){
          downNode();
        }else
        {
          sendHeartbeat();
        }
      }else if(NodeState.backup.equals(state)||NodeState.ready.equals(state)){
        if(isLostHeartBeat()&&canElect()){
          elect();
        }
      }else{
        if(canElect()){
          elect();
        }
      }
    }catch (Exception e){
      e.printStackTrace();
    }
  }

  private  boolean isUp(){
    return isUp(stateManager.getLocalIp());
  }

  private  NetworkInterface getNic(String ip) {
    NetworkInterface nic = null;
    try {
      InetAddress address = InetAddress.getByName(ip);
      nic = NetworkInterface.getByInetAddress(address);
    }catch (Exception ex){
      ex.printStackTrace();
    }
    return nic;
  }

  private  boolean isUp(String ip){
    NetworkInterface nic = getNic(ip);
    if(nic!=null){
      try {
        return nic.isUp();
      } catch (SocketException e) {
        e.printStackTrace();
      }
    }
    return false;
  }


}
