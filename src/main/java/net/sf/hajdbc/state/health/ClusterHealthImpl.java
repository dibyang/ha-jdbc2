package net.sf.hajdbc.state.health;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.DatabaseClusterListener;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.distributed.MembershipListener;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.state.DatabaseEvent;
import net.sf.hajdbc.state.distributed.DistributedStateManager;
import net.sf.hajdbc.state.distributed.NodeState;
import net.sf.hajdbc.util.HaJdbcThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusterHealthImpl implements Runnable, ClusterHealth, DatabaseClusterListener {

  private final static Logger logger = LoggerFactory.getLogger(ClusterHealthImpl.class);

  public static final int HEARTBEAT_LOST_MAX = 5;
  private long maxElectTime = 4 * 60*1000L;
  private DistributedStateManager stateManager;
  private final Arbiter arbiter;
  private volatile boolean unattended = true;
  private final ExecutorService executorService;

  private NodeState state = NodeState.offline;
  private final AtomicInteger counter = new AtomicInteger(0);
  private volatile long offsetTime = 0;
  private volatile long lastHeartbeat = 0;

  HeartBeatCommand beatCommand = new HeartBeatCommand();
  NodeHealthCommand healthCommand = new NodeHealthCommand();

  private final ScheduledExecutorService scheduledService = Executors.newScheduledThreadPool(1,
      HaJdbcThreadFactory.c("cluster-health-Thread"));


  public ClusterHealthImpl(DistributedStateManager stateManager) {
    this.stateManager = stateManager;
    this.stateManager.getDatabaseCluster().addListener(this);
    executorService = Executors.newFixedThreadPool(2,HaJdbcThreadFactory.c("cluster-executor-Thread"));
    stateManager.setExtContext(ClusterHealth.class.getName(),this);
    arbiter = new Arbiter(stateManager.getDatabaseCluster().getId());
  }

  @Override
  public void start(){
    String localIp = stateManager.getLocalIp();
    arbiter.setLocal(localIp);
    elect();
    if(state.equals(NodeState.offline)){
      throw new RuntimeException("not find host node.");
    }
    scheduledService.scheduleWithFixedDelay(this,4000,2000, TimeUnit.MILLISECONDS);
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
    Set databases = stateManager.getActiveDatabases();
    health.getActiveDBs().retainAll(databases);
    health.getActiveDBs().addAll(databases);
    return health;
  }

  @Override
  public NodeHealth getNodeHealth(Member member) {
    NodeHealthCommand cmd = new NodeHealthCommand();
    return (NodeHealth) stateManager.execute(cmd,member);
  }


  /**
   * Receive host node heart beat.
   */
  @Override
  public void receiveHeartbeat(long sendTime){
    logger.debug("receive host heart beat.");
    counter.set(0);
    lastHeartbeat = sendTime;
    offsetTime = sendTime -System.currentTimeMillis();

  }


  @Override
  public long getOffsetTime() {
    return offsetTime;
  }

  @Override
  public long getHostTime() {
    return System.currentTimeMillis()+offsetTime;
  }

  @Override
  public boolean canWrite() {
    return state.isCanUpdate();
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
    if(canWrite()){
      arbiter.update(token);
    }
  }

  /**
   * Send heartbeat.
   */
  private void sendHeartbeat(){
    logger.debug("host send heart beat.");
    stateManager.executeAll(beatCommand.preSend(),stateManager.getLocal());
    logger.debug("host send heart beat end.");
  }



  /**
   * Does it lost heart beat.
   * @return Does it lost heart beat
   */
  private boolean isLostHeartBeat(){
    int count = counter.incrementAndGet();
    if(count>= HEARTBEAT_LOST_MAX){
      counter.set(0);
      Map<Member, NodeHealth> all = stateManager.executeAll(healthCommand);

      //delete invalid data.
      remveInvalidReceive(all);
      Entry<Member, NodeHealth> host = findNodeByState(all, NodeState.host);
      boolean lost = (host == null);
      logger.info("lost heart beat = "+lost);
      return lost;
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
        DatabaseCluster cluster = stateManager.getDatabaseCluster();
        Database database = cluster.getLocalDatabase();
        if(cluster.isAlive(database, Level.WARN)) {
          return true;
        }else{
          logger.info("database not active.");
        }
      }
    }
    return false;
  }

  /**
   * start elect host node.
   */
  private synchronized void elect(){
    Lock lock = null;
    try {
      lock = stateManager.getDatabaseCluster().getLockManager().onlyLock("HOST_ELECT");
      if(lock.tryLock()){
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
              if(waitTime<16){
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
      }else{
        logger.info("get elect lock fail.");
      }

    }catch (Exception e){
      logger.warn("",e);
    }finally {
      if(lock!=null){
        lock.unlock();
      }
    }

  }

  private Entry<Member, NodeHealth> doElect(long beginElectTime) {
    Map<Member, NodeHealth> all = stateManager.executeAll(healthCommand);

    //delete invalid data.
    remveInvalidReceive(all);

    Entry<Member, NodeHealth> host = null;
    if(all.size()>=stateManager.getMembers().size()){
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
    }else{
      logger.info("some nodes are not responding.");
    }
    return host;

  }

  @Override
  public void host(Member host, long token){
    if(host!=null){
      if(stateManager.getLocal().equals(host)){
        if(!state.equals(NodeState.host)){
          setState(NodeState.host);
          Database database = stateManager.getDatabaseCluster().getLocalDatabase();
          if(!database.isActive()){
            stateManager.activated(new DatabaseEvent(database));
          }
        }
      }else{
        setState(NodeState.ready);
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
    if(!isUp()||!arbiter.isObservable()||!stateManager.getDatabaseCluster().getLocalDatabase().isActive()){
      return true;
    }
    return false;
  }

  private void downNode(){
    setState(NodeState.offline);
  }


  @Override
  public void run() {
    try {
      if(NodeState.host.equals(state)){
        if(findOtherHost()){
          if(canElect()){
            elect();
          }
        }else{
          if(isNeedDown()){
            downNode();
          }else
          {
            arbiter.getLocal().setOnlyHost((stateManager.getActiveDatabases().size()<2));
            sendHeartbeat();
            executorService.submit(new Runnable() {
              @Override
              public void run() {
                restoreDatabaseForReady();
              }
            });

          }
        }

      }else {
        DatabaseCluster databaseCluster = stateManager.getDatabaseCluster();
        if(NodeState.backup.equals(state)||NodeState.ready.equals(state)){
          arbiter.getLocal().setOnlyHost(false);
          Database database = databaseCluster.getLocalDatabase();
          if(NodeState.backup.equals(state)){
            if(isNeedDown()) {
              downNode();
            }
          }else{
            if(database.isActive()) {
              setState(NodeState.backup);
            }
          }
          if(isLostHeartBeat()&&canElect()){
            elect();
          }
        }else{
          Database database = databaseCluster.getLocalDatabase();
          if(database.isActive()) {
            databaseCluster.deactivate(database,stateManager);
          }
          if(canElect()){
            elect();
          }
        }
      }

    }catch (Exception e){
      logger.warn("",e);
    }
  }

  /**
   * find other host or not
   * @return find other host or not
   */
  private boolean findOtherHost() {
    NodeHealthCommand cmd = new NodeHealthCommand();
    Map all = stateManager.executeAll(cmd, stateManager.getLocal());
    if(all.size()>0){
      Iterator iterator = all.values().iterator();
      while(iterator.hasNext()){
        NodeHealth next = (NodeHealth)iterator.next();
        if(next!=null&&next.getState().equals(NodeState.host)){
          return true;
        }
      }
    }
    return false;
  }
  DatabaseRestoreCommand cmd= new DatabaseRestoreCommand();

  private void restoreDatabaseForReady() {
    DatabaseCluster databaseCluster = stateManager.getDatabaseCluster();
    synchronized (databaseCluster){
      if(databaseCluster.getDialect().isSupportRestore()){
        Database database = databaseCluster.getLocalDatabase();
        if(stateManager.getActiveDatabases().size()>0
            &&stateManager.getMembers().size()>1){
          Map<Member, NodeHealth> all = stateManager.executeAll(healthCommand,stateManager.getLocal());
          removeInvalidReceiveByState(all, NodeState.ready);
          if(all.size()>0){
            Lock lock = databaseCluster.getLockManager().writeLock(null);
            try {
              lock.lockInterruptibly();
              Path path = PathHelper.helper.get("backup.bak");
              if(Files.exists(path)){
                Files.delete(path);
              }
              databaseCluster.backup(database,path.toFile());

              if(Files.exists(path)){
                cmd.setBytes(Files.readAllBytes(path));
                Iterator<Entry<Member, NodeHealth>> iterator = all.entrySet().iterator();
                if(iterator.hasNext()){
                  Member member = iterator.next().getKey();
                  String dbId = (String)stateManager.execute(cmd,member);
                  if(dbId!=null){
                    databaseCluster.activate(databaseCluster.getDatabase(dbId), databaseCluster.getStateManager());
                  }
                }
              }
            }catch (Exception e){
              logger.warn("",e);
            }finally {
              lock.unlock();
            }
          }
        }
      }
    }

  }

  private void remveInvalidReceive(Map<Member, NodeHealth> all) {
    //delete invalid data.
    removeInvalidReceiveByState(all,null);
  }

  private void removeInvalidReceiveByState(Map<Member, NodeHealth> all,NodeState state) {
    //delete invalid data.
    Iterator<Entry<Member, NodeHealth>> iterator = all.entrySet().iterator();
    while (iterator.hasNext()) {
      Entry<Member, NodeHealth> next = iterator.next();
      if (next.getValue() == null||(state!=null&&!state.equals(next.getValue().getState()))) {
        iterator.remove();
      }
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

  private void checkActiveDatabases(Set<String> activeDatabases) {
    DatabaseCluster databaseCluster = stateManager.getDatabaseCluster();
    Set databases = stateManager.getActiveDatabases();
    for(String db:activeDatabases){
      if(!databases.contains(db)){
        Database database = databaseCluster.getDatabase(db);
        if(!database.isActive()){
          logger.info("database:"+db+" is reactive.");
          databaseCluster.getBalancer().add(database);
          database.setActive(true);
        }
      }
    }
  }

  @Override
  public void activated(DatabaseEvent event) {
    if(!state.equals(NodeState.host)){
      executorService.submit(new Runnable() {
        @Override
        public void run() {
          Map<Member, NodeHealth> all = stateManager.executeAll(healthCommand);

          //delete invalid data.
          remveInvalidReceive(all);
          Entry<Member, NodeHealth> host = findNodeByState(all, NodeState.host);
          if(host!=null){
            checkActiveDatabases(host.getValue().getActiveDBs());
          }
        }
      });
    }
  }

  @Override
  public void deactivated(DatabaseEvent event) {

  }
}
