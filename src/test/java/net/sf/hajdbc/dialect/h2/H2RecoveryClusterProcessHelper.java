package net.sf.hajdbc.dialect.h2;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import net.sf.hajdbc.codec.SimpleCodecFactory;
import net.sf.hajdbc.distributed.jgroups.JGroupsCommandDispatcherFactory;
import net.sf.hajdbc.lock.semaphore.SemaphoreLockManagerFactory;
import net.sf.hajdbc.sql.DatabaseClusterImpl;
import net.sf.hajdbc.sql.DriverDatabase;
import net.sf.hajdbc.sql.DriverDatabaseClusterConfiguration;
import net.sf.hajdbc.state.health.MountPathHolder;
import net.sf.hajdbc.state.simple.SimpleStateManagerFactory;
import net.sf.hajdbc.sync.DumpRestoreSynchronizationStrategy;
import net.sf.hajdbc.sync.SynchronizationContext;
import org.h2.tools.Server;

/** 隔离 Linux 双机的完整集群入口：真实选举、启动、激活、元数据与同步，不注入业务集群测试桩。 */
public final class H2RecoveryClusterProcessHelper {
  private static Path testDirectory;
  private static final java.util.concurrent.atomic.AtomicInteger slowCalls = new java.util.concurrent.atomic.AtomicInteger();
  private H2RecoveryClusterProcessHelper() { }

  /** 测试专用 H2 服务端门闩，真实 RPC 超时不结束这个 SQL 调用。 */
  public static int pauseImport() throws Exception {
    slowCalls.incrementAndGet();
    long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    while (!Files.exists(testDirectory.resolve("slow-release"))) {
      if (System.nanoTime() >= end) throw new IllegalStateException("Test import was not released");
      Thread.sleep(20);
    }
    return 1;
  }

  public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[0]); String id = args[1]; String first = args[2], second = args[3];
    boolean source = id.equals("source"); String localIp = source ? first : second;
    Path local = root.resolve(id); Files.createDirectories(local);
    testDirectory = local;
    Files.write(local.resolve("pid"), java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0].getBytes(StandardCharsets.US_ASCII));
    System.setProperty("app.root", local.toString());
    System.setProperty("ha-jdbc.config-dir", local.resolve("config").toString());
    System.setProperty("ha-jdbc.manager-fs-ip-file", local.resolve("manager-ips").toString());
    System.setProperty("ha-jdbc.h2-recovery-dir", local.resolve("recovery").toString());
    System.setProperty("ha-jdbc.sync.allowed-roots", root.toString());
    Path mounts = local.resolve("mounts");
    Files.write(mounts, ("none " + local.resolve("arbiter") + " LeoFS rw 0 0\n").getBytes(StandardCharsets.UTF_8));
    System.setProperty("ha-jdbc.mounts-file", mounts.toString());
    MountPathHolder.H.fileChanged(mounts.toFile());
    System.setProperty("jgroups.bind_addr", localIp);
    System.setProperty("jgroups.tcp.bind_port", "17810");
    System.setProperty("jgroups.tcp.bind_port_state", "17811");
    System.setProperty("jgroups.tcpping.initial_hosts", first + "[17810]," + second + "[17810]");
    System.setProperty("jgroups.tcpping.initial_hosts_state", first + "[17811]," + second + "[17811]");
    if (args.length > 4 && args[4].equals("owner")) {
      // 仅缩短本夹具旧进程移除时间，让服务端门闩覆盖新成员接入；产品默认不变。
      System.setProperty("jgroups.fd_all.timeout", "2500"); System.setProperty("jgroups.fd_all.interval", "500");
      System.setProperty("jgroups.verify_suspect.timeout", "500");
    }
    // 只限制隔离夹具的启动等待；不修改生产值或选举/仲裁规则。
    System.setProperty(DatabaseClusterImpl.STARTUP_ACTIVE_DATABASE_TIMEOUT_PROPERTY, "45000");
    System.setProperty(DatabaseClusterImpl.STARTUP_LOCAL_DATABASE_TIMEOUT_PROPERTY, "15000");
    Server server = Server.createTcpServer("-tcpPort", "19102", "-tcpAllowOthers", "-ifNotExists").start();
    DatabaseClusterImpl<java.sql.Driver, DriverDatabase> cluster = null;
    try {
      DriverDatabase sourceDb = database("source", first, root), targetDb = database("target", second, root);
      DriverDatabase mine = source ? sourceDb : targetDb;
      if (!Files.exists(local.resolve("cluster-db.mv.db"))) {
        try (Connection connection = DriverManager.getConnection(mine.getLocation(), "sa", ""); Statement statement = connection.createStatement()) {
          statement.execute("CREATE TABLE BUSINESS_DATA(ID INT)"); statement.execute("INSERT INTO BUSINESS_DATA VALUES(" + (source ? 42 : 9) + ")");
        }
      }
      DriverDatabaseClusterConfiguration config = new DriverDatabaseClusterConfiguration();
      config.setDatabases(Arrays.asList(sourceDb, targetDb));
      config.setCodecFactory(clusterId -> new SimpleCodecFactory());
      config.setLockManagerFactory(new SemaphoreLockManagerFactory());
      config.setStateManagerFactory(new SimpleStateManagerFactory());
      config.setDialectFactory(new H2DialectFactory() {
        @Override public net.sf.hajdbc.dialect.Dialect createDialect() {
          return new H2Dialect() {
            @Override public void dump(SynchronizationContext context, net.sf.hajdbc.Database database,
                net.sf.hajdbc.codec.Decoder decoder, java.io.File file, boolean dataOnly) throws Exception {
              super.dump(context, database, decoder, file, dataOnly);
              if (Files.exists(local.resolve("fail-import"))) {
                Files.write(file.toPath(), "\nINVALID_FULL_CLUSTER_TEST_STATEMENT;\n".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
              }
              if (Files.exists(local.resolve("slow-import"))) Files.write(file.toPath(), ("\nCREATE ALIAS IF NOT EXISTS TEST_SLOW_IMPORT FOR \""
                  + H2RecoveryClusterProcessHelper.class.getName() + ".pauseImport\";\nCALL TEST_SLOW_IMPORT();\n").getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
              if (Files.exists(local.resolve("fail-import")) || Files.exists(local.resolve("slow-import")))
                net.sf.hajdbc.util.ZipUtils.compressFile(file.getPath(), file.getPath() + ".zip");
            }
          };
        }
      });
      if (args.length > 4 && (args[4].equals("auto") || args[4].equals("owner"))) {
        config.setAutoActivationExpression(new net.sf.hajdbc.util.concurrent.cron.CronExpression("0/3 * * * * ?"));
      }
      config.setMBeanRegistrar(null);
      DumpRestoreSynchronizationStrategy strategy = new DumpRestoreSynchronizationStrategy();
      config.getSynchronizationStrategyMap().put("dump", strategy); config.setDefaultSynchronizationStrategy("dump");
      JGroupsCommandDispatcherFactory factory = new JGroupsCommandDispatcherFactory();
      factory.setStack("tcp.xml"); factory.setName(localIp); factory.setTimeout(6000); config.setDispatcherFactory(factory);
      cluster = new DatabaseClusterImpl<>("h2-full-cluster-test", config, null);
      // 先启动两台进程再等待 ready；start 中的选举仍按产品原有条件执行。
      cluster.start(); Files.write(local.resolve("ready"), "READY".getBytes(StandardCharsets.UTF_8));
      try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
        for (String line; (line = input.readLine()) != null;) {
          String[] request = line.split(" ", 2); String result;
          try {
            switch (request[1]) {
              case "STATUS": result = "state=" + cluster.getClusterHealth().getState() + ",active=" + cluster.getActiveDatabases()
                  + ",eligible=" + H2RecoveryCoordinator.get(cluster).isEligible(mine.getId()); break;
              case "PROBE":
                net.sf.hajdbc.state.distributed.DistributedStateManager manager = (net.sf.hajdbc.state.distributed.DistributedStateManager) cluster.getDistributedManager();
                net.sf.hajdbc.distributed.MembershipSnapshot stateView = manager.getDispatcher().membershipSnapshot();
                net.sf.hajdbc.distributed.MembershipSnapshot lockView = ((net.sf.hajdbc.lock.distributed.DistributedLockManager) cluster.getLockManager()).membershipSnapshot();
                StringBuilder diagnostics = new StringBuilder();
                for (DriverDatabase database : Arrays.asList(sourceDb, targetDb)) {
                  byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest((cluster.getId() + "\n" + database.getId() + "\n"
                      + H2RecoveryRuntime.databaseName(database.getLocation())).getBytes(StandardCharsets.UTF_8));
                  StringBuilder identity = new StringBuilder(); for (byte value : digest) identity.append(String.format("%02x", value & 255));
                  java.util.Map<net.sf.hajdbc.distributed.Member, net.sf.hajdbc.state.sync.H2RecoveryCommand.Reply> replies = manager.executeAll(
                      net.sf.hajdbc.state.sync.H2RecoveryCommand.probe(database.getId(), identity.toString()));
                  for (java.util.Map.Entry<net.sf.hajdbc.distributed.Member, net.sf.hajdbc.state.sync.H2RecoveryCommand.Reply> entry : replies.entrySet()) {
                    net.sf.hajdbc.state.sync.H2RecoveryCommand.Reply reply = entry.getValue();
                    diagnostics.append(database.getId()).append('/').append(entry.getKey()).append(':');
                    if (reply == null) diagnostics.append("null");
                    else diagnostics.append(reply.status).append(" local=").append(reply.localDatabase).append(" views=").append(reply.matchesViews(entry.getKey(), stateView, lockView))
                        .append(" identity=").append(identity.toString().equals(reply.targetIdentity)).append(" intent=").append(reply.intent != null)
                        .append(" decision=").append(reply.intent == null ? "none" : reply.intent.decision).append(" running=").append(reply.running)
                        .append(" state=").append(reply.stateView == null ? "none" : reply.stateView.getViewId())
                        .append(" lock=").append(reply.lockView == null ? "none" : reply.lockView.getViewId());
                    diagnostics.append(';');
                  }
                }
                result = diagnostics.toString(); break;
              case "SYNC":
                String replica = source ? "target" : "source";
                cluster.deactivate(replica); cluster.activate(replica, "dump"); result = "OK"; break;
              case "FAIL_ON":
                Files.write(local.resolve("fail-import"), new byte[] {1});
                cluster.deactivate(source ? "target" : "source"); result = "OK"; break;
              case "FAIL_OFF": Files.deleteIfExists(local.resolve("fail-import")); result = "OK"; break;
              case "SLOW_ON":
                Files.write(local.resolve("slow-import"), new byte[] {1});
                cluster.deactivate(source ? "target" : "source"); result = "OK"; break;
              case "SLOW_OFF": Files.deleteIfExists(local.resolve("slow-import")); result = "OK"; break;
              case "SLOW_RELEASE": Files.write(local.resolve("slow-release"), new byte[] {1}); result = "OK"; break;
              case "SLOW_COUNT": result = Integer.toString(slowCalls.get()); break;
              case "TRY_READ_LOCK":
                java.util.concurrent.locks.Lock read = cluster.getLockManager().readLock(null);
                boolean acquired = read.tryLock(); if (acquired) read.unlock(); result = Boolean.toString(acquired); break;
              case "MEMBERS":
                net.sf.hajdbc.state.distributed.DistributedStateManager membersManager = (net.sf.hajdbc.state.distributed.DistributedStateManager) cluster.getDistributedManager();
                result = "state=" + membersManager.getDispatcher().membershipSnapshot().getMembers().size() + ",lock="
                    + ((net.sf.hajdbc.lock.distributed.DistributedLockManager) cluster.getLockManager()).membershipSnapshot().getMembers().size(); break;
              case "SELF_RECORD": case "PEER_RECORD":
                DriverDatabase selected = request[1].equals("SELF_RECORD") ? mine : source ? targetDb : sourceDb;
                net.sf.hajdbc.state.distributed.DistributedManager managerForRecord = cluster.getDistributedManager();
                net.sf.hajdbc.state.sync.H2RecoveryCommand.Handler handler = (net.sf.hajdbc.state.sync.H2RecoveryCommand.Handler)
                    managerForRecord.getExtContext(net.sf.hajdbc.state.sync.H2RecoveryCommand.Handler.class.getName());
                net.sf.hajdbc.state.sync.H2RecoveryCommand.Reply record = handler.handle(probe(cluster, selected));
                result = "decision=" + record.intent.decision + ",epoch=" + record.intent.epoch + ",running=" + record.running; break;
              case "CRASH": Runtime.getRuntime().halt(23); throw new AssertionError("halt returned");
              case "ROWS":
                try (Connection connection = DriverManager.getConnection(mine.getLocation(), "sa", ""); Statement statement = connection.createStatement();
                    java.sql.ResultSet rows = statement.executeQuery("SELECT ID FROM BUSINESS_DATA ORDER BY ID")) {
                  StringBuilder values = new StringBuilder(); while (rows.next()) values.append(rows.getInt(1)).append(','); result = values.toString();
                } break;
              case "STOP": Files.write(local.resolve(request[0]), "OK".getBytes(StandardCharsets.UTF_8)); return;
              default: throw new IllegalArgumentException("Unknown test command");
            }
          } catch (Exception failure) { failure.printStackTrace(); result = "ERROR:" + failure.getClass().getSimpleName(); }
          Files.write(local.resolve(request[0]), result.getBytes(StandardCharsets.UTF_8));
        }
      }
    } finally {
      try { if (cluster != null) cluster.stop(); } finally { server.stop(); }
      Files.write(local.resolve("stopped"), "STOPPED".getBytes(StandardCharsets.US_ASCII));
      for (Thread thread : Thread.getAllStackTraces().keySet()) {
        if (thread.isAlive() && !thread.isDaemon()) System.out.println("TEST_EXIT_NON_DAEMON=" + thread.getName());
      }
      // 原有 TokenStore 的 JVM 级静态池不归 cluster.stop 管理；仅独立测试进程显式收口。
      java.lang.reflect.Field field = net.sf.hajdbc.state.health.TokenStore.class.getDeclaredField("timeoutUtil");
      field.setAccessible(true); ((net.sf.hajdbc.state.health.TimeoutUtil) field.get(null)).shutdown();
    }
  }

  private static DriverDatabase database(String id, String ip, Path root) {
    DriverDatabase database = new DriverDatabase(); database.setId(id); database.setIp(ip); database.setUser("sa"); database.setPassword("");
    database.setLocation("jdbc:h2:tcp://" + ip + ":19102/" + root.resolve(id).resolve("cluster-db")); return database;
  }

  private static net.sf.hajdbc.state.sync.H2RecoveryCommand probe(DatabaseClusterImpl<?, ?> cluster, DriverDatabase database) throws Exception {
    byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest((cluster.getId() + "\n" + database.getId() + "\n"
        + H2RecoveryRuntime.databaseName(database.getLocation())).getBytes(StandardCharsets.UTF_8));
    StringBuilder identity = new StringBuilder(); for (byte value : bytes) identity.append(String.format("%02x", value & 255));
    return net.sf.hajdbc.state.sync.H2RecoveryCommand.probe(database.getId(), identity.toString());
  }
}
