package net.sf.hajdbc.state.health;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.DatabaseClusterListener;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.state.DatabaseEvent;
import net.sf.hajdbc.state.distributed.DistributedStateManager;
import net.sf.hajdbc.state.distributed.NodeState;
import net.sf.hajdbc.util.HaJdbcThreadFactory;
import net.sf.hajdbc.util.StopWatch;
import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusterHealthImpl implements Runnable, ClusterHealth, DatabaseClusterListener {

  private final static Logger logger = LoggerFactory.getLogger(ClusterHealthImpl.class);

  public static final int MAX_TRY_LOCK = 10;
  public static final int MAX_INACTIVATED = 6;
  public static final String HOST_ELECT = "HOST_ELECT";
  private long maxElectTime = 4 * 60*1000L;
  private DistributedStateManager stateManager;
  private final Arbiter arbiter;
  private volatile int token = 0;
  private volatile boolean unattended = true;
  private final ExecutorService executorService;

  private NodeState state = NodeState.offline;
  private final AtomicInteger counter = new AtomicInteger(0);
  private volatile long offsetTime = 0;
  private volatile Member host = null;
  private FileWatchDog fileWatchDog;

  HeartBeatCommand beatCommand = new HeartBeatCommand();
  NodeHealthCommand healthCommand = new NodeHealthCommand();

  private final ScheduledExecutorService scheduledService = Executors.newScheduledThreadPool(1,
      HaJdbcThreadFactory.c("cluster-health-Thread"));


  public ClusterHealthImpl(DistributedStateManager stateManager) {
    this.stateManager = stateManager;
    this.stateManager.getDatabaseCluster().addListener(this);
    executorService = Executors.newFixedThreadPool(2,HaJdbcThreadFactory.c("cluster-executor-Thread"));
    stateManager.setExtContext(ClusterHealth.class.getName(),this);
    fileWatchDog = new FileWatchDog(new File("/proc/mounts"), MountPathHolder.H);
    arbiter = new Arbiter(stateManager.getDatabaseCluster().getId());
  }

  @Override
  public void start(){
    fileWatchDog.watch();
    String localIp = stateManager.getLocalIp();
    arbiter.setLocal(localIp);
    this.run();
    scheduledService.scheduleWithFixedDelay(this,1000,1000, TimeUnit.MILLISECONDS);
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
    DateTime now = new DateTime();
    long offset = ((sendTime - now.getMillis())/1000)*1000;
    if(offset!=offsetTime) {
      offsetTime = offset;
      DateTimeUtils.setCurrentMillisOffset(offsetTime);
    }

  }


  @Override
  public long getOffsetTime() {
    return offsetTime;
  }

  @Override
  public long getHostTime() {
    DateTime now = new DateTime();
    return now.getMillis();
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
      //避免数据同步时间过长导致的心跳丢失
      counter.set(0);
    }
  }

  void changeState(NodeState oldState,NodeState newState){
    logger.info("node state from "+oldState+" to "+newState);
    stateManager.getDatabaseCluster().changeState(oldState,newState);
  }

  UpdateTokenCommand updateTokenCommand = new UpdateTokenCommand();

  @Override
  public void incrementToken(){
    if(token<=0){
      token = 1;
    }
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
    stateManager.executeAll(beatCommand.preSend(),stateManager.getLocal());
    logger.debug("host send heart beat end.");
  }


  private boolean isLostHost(){
    boolean lost = (host == null);
    return lost;
  }

  /**
   * Returns can it elect.
   * @return Can it elect.
   */
  private boolean canElect(){
    if(isUp()&&isLostHost()){
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
  private synchronized void elect() throws InterruptedException {
    Lock lock = stateManager.getDatabaseCluster().getLockManager().onlyLock(HOST_ELECT);
    lock.lockInterruptibly();
    try{
      if(isLostHost()) {
        logger.info("host elect begin.");
        StopWatch stopWatch = StopWatch.createStarted();
        long waitTime = 2;
        long beginElectTime = System.nanoTime();
        Entry<Member, NodeHealth> host = doElect(beginElectTime);
        while (host == null && unattended) {
          host = doElect(beginElectTime);
          if (host == null) {
            logger.info("can not elect host node. try elect again after " + waitTime + "s");
            Thread.sleep(waitTime * 1000);
            if (waitTime < 16) {
              waitTime = waitTime * 2;
            }
          } else {
            break;
          }
        }
        if (host != null) {
          logger.info("host elect:{}. cost time:{}", host.getKey(), stopWatch.toString());
          this.host(host.getKey(), host.getValue().getLocal());
          HostCommand hostCommand = new HostCommand();
          hostCommand.setHost(host.getKey());
          hostCommand.setToken(host.getValue().getLocal());
          stateManager.executeAll(hostCommand, host.getKey());
        }
        logger.info("host elect end. cost time:{}", stopWatch.toString());
      }
    }catch (Exception e){
      logger.warn("",e);
    }finally {
      lock.unlock();
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
        //满足最小节点数
        if(all.size()>=getMinNodeCount()){
          host = findNodeByToken(all);
        }
      }
      if(host==null){
        long time = System.nanoTime() - beginElectTime;
        //选举超时
        if((TimeUnit.NANOSECONDS.toMillis(time) > maxElectTime)){
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

  private int getMinNodeCount(){
    int count = stateManager.getDatabaseCluster().getNodeCount();
    return count;
  }

  @Override
  public void host(Member host, long token){
    this.host = host;
    if(host!=null){
      if(stateManager.getLocal().equals(host)){
        if(!state.equals(NodeState.host)){
          setState(NodeState.host);
          DatabaseCluster databaseCluster = stateManager.getDatabaseCluster();
          Database database = databaseCluster.getLocalDatabase();
          if(!database.isActive()){
            stateManager.activated(new DatabaseEvent(database));
            databaseCluster.getBalancer().add(database);
            database.setActive(true);
          }
        }
      }else{
        setState(NodeState.ready);
      }
    }else{
      setState(NodeState.offline);
    }

  }

  @Override
  public Member getHost() {
    return host;
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

  private final AtomicInteger dbInActivated = new AtomicInteger();

  /**
   * 6以上次才认为数据库不是活的
   * @param database
   * @return
   */
  private boolean isActiveLocalDb(Database database){
    boolean active = true;
    if(database!=null&&!database.isActive()){
      dbInActivated.incrementAndGet();
    }else{
      dbInActivated.set(0);
    }
    if(dbInActivated.get()> MAX_INACTIVATED){
      active = false;
      dbInActivated.set(0);
    }
    return active;
  }

  /**
   * Does it need to be down.
   * @return Does it need to be down?
   */
  private boolean isNeedDown(){
    boolean up = isUp();
    boolean observable = arbiter.isObservable();
    Database database = stateManager.getDatabaseCluster().getLocalDatabase();
    boolean active = isActiveLocalDb(database);
    if(!up ||!observable ||!active){
      String db = database!=null?database.getId():"";
      logger.warn("node need down. up={}, observable={}, db active={} db={}",up,observable,active, db);
      return true;
    }
    return false;
  }

  private void downNode(){
    setState(NodeState.offline);
  }


  @Override
  public void run() {
    fileWatchDog.watch();
    try {
      if(NodeState.host.equals(state)){
        watchHostNode();
      }else {
        watchNotHostNode();
      }

    }catch (Exception e){
      logger.warn("",e);
    }
  }

  private void watchHostNode() throws InterruptedException {
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
            updateNewToken();
          }
        });
      }
    }
  }

  private void watchNotHostNode() throws InterruptedException {
    DatabaseCluster databaseCluster = stateManager.getDatabaseCluster();
    if(NodeState.backup.equals(state)||NodeState.ready.equals(state)){
      arbiter.getLocal().setOnlyHost(false);
      if(NodeState.backup.equals(state)){
        if(isNeedDown()) {
          downNode();
        }
      }else{
        if(isActiveNode(databaseCluster)) {
          setState(NodeState.backup);
        }
      }
      if(this.isLostHost()&&canElect()){
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

  private boolean isActiveNode(DatabaseCluster databaseCluster) {
    boolean active = false;
    try {
      ServiceLoader<NodeActiveChecker> load = ServiceLoader.load(NodeActiveChecker.class);
      Iterator<NodeActiveChecker> iterator = load.iterator();
      while (iterator.hasNext()) {
        active = iterator.next().isActive(databaseCluster);
        if (!active) {
          break;
        }
      }
    }catch (Exception e){
      e.printStackTrace();
      active = false;
    }
    return active;
  }

  private void updateNewToken() {
    if(token>0) {
      long newToken = arbiter.getLocal().getToken() + token;
      logger.debug("update newToken=" + newToken);
      token = 0;
      updateTokenCommand.setToken(newToken);
      stateManager.executeAll(updateTokenCommand);
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
    return isUp(stateManager.getLocalIp(), 1);
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

  /**
   *
   * @param ip 对应的IP
   * @param failedTryCount 失败后的尝试次数
   * @return
   */
  private  boolean isUp(String ip, int failedTryCount){
    NetworkInterface nic = getNic(ip);
    if(nic!=null){
      try {
        return nic.isUp();
      } catch (SocketException e) {
        logger.warn("is up fail.", e);
        return true;
      }
    }else{
      logger.info("not find nic for ip {}", ip);
      if(failedTryCount>1) {
        try {
          Thread.sleep(1000);
          return isUp(ip, failedTryCount-1);
        } catch (InterruptedException e) {
          //e.printStackTrace();
          return false;
        }
      }else{
        return false;
      }
    }
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

  @Override
  public void added(Member member) {

  }

  @Override
  public void removed(Member member) {
    if(member!=null&&member.equals(host)){
      host = null;
      logger.info("lost Host:{}", member);

    }
  }
}
