package net.sf.hajdbc.state.health;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Iterator;
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
  private DistributedStateManager<Z, D> stateManager;
  private final Arbiter arbiter;
  private volatile boolean unattended = false;
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
  public void receiveHeartbeat(long token){
    counter.set(0);
    lastHeartbeat = System.currentTimeMillis();
    if(state.equals(NodeState.host)||state.equals(NodeState.backup)){
      arbiter.update(token);
    }
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

  }

  /**
   * Send heartbeat.
   */
  private void sendHeartbeat(){
    beatCommand.setToken(arbiter.getLocal().getToken()+1);
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
    Map<Member,NodeHealth> all = stateManager.executeAll(healthCommand);

    //delete invalid data.
    Iterator<Entry<Member, NodeHealth>> iterator = all.entrySet().iterator();
    while(iterator.hasNext()){
      Entry<Member, NodeHealth> next = iterator.next();
      if(next.getValue()==null){
        iterator.remove();
      }
    }
    Member host = null;
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
      if(unattended){

      }
    }
    if(host!=null){
      HostCommand hostCommand = new HostCommand();
      hostCommand.setHost(host);
      stateManager.executeAll(hostCommand);
    }

    logger.info("host elect end.");
  }

  public void host(Member host){
    if(host!=null){
      if(stateManager.getLocal().equals(host)){
        setState(NodeState.host);
        stateManager.activated(new DatabaseEvent(stateManager.getDatabaseCluster().getLocalDatabase()));
      }else{
        setState(NodeState.ready);
      }
    }else{
      setState(NodeState.offline);
    }

  }

  private Member findNodeByEmpty(Map<Member, NodeHealth> all) {
    Member find = null;
    Iterator<Entry<Member, NodeHealth>> iterator = all.entrySet().iterator();
    while(iterator.hasNext()) {
      Entry<Member, NodeHealth> next = iterator.next();
      NodeHealth health = next.getValue();
      if (next.getValue() != null) {
        if (health != null && health.isEmpty()) {
          find = next.getKey();
          break;
        }
      }
    }
    return find;
  }

  private Member findNodeByValidLocal(Map<Member, NodeHealth> all) {
    Member find = null;
    Iterator<Entry<Member, NodeHealth>> iterator = all.entrySet().iterator();
    while(iterator.hasNext()) {
      Entry<Member, NodeHealth> next = iterator.next();
      NodeHealth health = next.getValue();
      if (next.getValue() != null) {
        if (health != null && health.isValidLocal()) {
          find = next.getKey();
          break;
        }
      }
    }
    return find;
  }

  private Member findNodeByState(Map<Member, NodeHealth> all,NodeState state) {
    Member find = null;
    Iterator<Entry<Member, NodeHealth>> iterator = all.entrySet().iterator();
    while(iterator.hasNext()) {
      Entry<Member, NodeHealth> next = iterator.next();
      NodeHealth health = next.getValue();
      if (next.getValue() != null) {
        if (health != null && health.getState().equals(find)) {
          find = next.getKey();
          break;
        }
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