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

public class ClusterHealthImpl implements Runnable, ClusterHealth {
  private final static Logger logger = LoggerFactory.getLogger(ClusterHealthImpl.class);

  public static final int HEARTBEAT_LOST_MAX = 3;
  private long maxElectTime = 5 * 60*1000L;
  private DistributedStateManager stateManager;
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


  public ClusterHealthImpl(DistributedStateManager stateManager) {
    this.stateManager = stateManager;
    stateManager.setExtContext(this);
    arbiter = new Arbiter();
  }

  @Override
  public void start(){
    String localIp = stateManager.getLocalIp();
    arbiter.setLocal(localIp);
    elect();
    if(state.equals(NodeState.offline)){
      throw new RuntimeException("not find host node.");
    }
    scheduledService.scheduleWithFixedDelay(this,0,2000, TimeUnit.MILLISECONDS);
  }
  @Override
  public void stop(){
    scheduledService.shutdown();
  }

  @Override
  public NodeHealth getNodeHealth(){
    NodeHealth health = new NodeHealth();
    health.setState(state);
    health.setLastOnlyHost(arbiter.getLocal().isOnlyHost());
    health.setLocal(arbiter.getLocal().getToken());
    health.setArbiter(arbiter.getArbiter().getToken());
    return health;
  }


  /**
   * Receive host node heart beat.
   */
  @Override
  public void receiveHeartbeat(){
    counter.set(0);
    lastHeartbeat = System.currentTimeMillis();
  }

  @Override
  public NodeState getState() {
    return state;
  }

  @Override
  public boolean isHost(){
    return NodeState.host.equals(state);
  }

  @Override
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

  @Override
  public void incrementToken(){
    long token = arbiter.getLocal().getToken() + 1;
    updateTokenCommand.setToken(token);
    stateManager.executeAll(updateTokenCommand);
  }

  @Override
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
    long waitTime = 2;
    long beginElectTime = System.currentTimeMillis();
    Entry<Member, NodeHealth> host = doElect(beginElectTime);
    while(host==null&&unattended){
      host = doElect(beginElectTime);
      if(host==null){
        logger.info("can not elect host node. try elect again after "+waitTime+"s");
        try {
          Thread.sleep(waitTime*1000);
          if(waitTime<30){
            waitTime=waitTime*2;
          }
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }else{
        break;
      }
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
    //not find host node. find backup node
    if(host==null){
      host = findNodeByState(all, NodeState.backup);
    }

    //not find backup node. find by valid local node
    if(host==null){
      host = findNodeByValidLocal(all);
    }

    //not find valid local node. find last only host node
    if(host==null){
      host = findNodeByLastOnlyHost(all);
    }

    //not find last only host node. find empty node
    if(host==null){
      host = findNodeByEmpty(all);
    }
    if(host==null){
      long time = System.currentTimeMillis() - beginElectTime;
      if(time > maxElectTime){
        host = findNodeByToken(all);
      }
    }
    if(host!=null){
      logger.info("find host node "+host.getKey()+".");
    }
    return host;

  }

  @Override
  public void host(Member host, long token){
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


  private Entry<Member, NodeHealth> findNodeByLastOnlyHost(Map<Member, NodeHealth> all) {
    Entry<Member, NodeHealth> find = null;
    Iterator<Entry<Member, NodeHealth>> iterator = all.entrySet().iterator();
    while(iterator.hasNext()) {
      Entry<Member, NodeHealth> next = iterator.next();
      NodeHealth health = next.getValue();
      if (health != null && health.isLastOnlyHost()) {
        if(find==null||health.getLocal()>find.getValue().getLocal()){
          find = next;
        }
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
      if (health != null && health.getState().equals(state)) {
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
          arbiter.getLocal().setOnlyHost((stateManager.getActiveDatabases().size()<2));

          sendHeartbeat();
        }
      }else if(NodeState.backup.equals(state)||NodeState.ready.equals(state)){
        arbiter.getLocal().setOnlyHost(false);
        if(NodeState.ready.equals(state)){
          if(stateManager.getDatabaseCluster().getLocalDatabase().isActive()) {
            setState(NodeState.backup);
          }
        }
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
