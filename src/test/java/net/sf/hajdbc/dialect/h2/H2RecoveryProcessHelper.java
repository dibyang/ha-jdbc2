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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.codec.Decoder;
import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.distributed.CommandDispatcher;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.distributed.MembershipListener;
import net.sf.hajdbc.distributed.Stateful;
import net.sf.hajdbc.distributed.jgroups.AddressMember;
import net.sf.hajdbc.distributed.jgroups.JGroupsCommandDispatcherFactory;
import net.sf.hajdbc.lock.distributed.DistributedLockManager;
import net.sf.hajdbc.lock.semaphore.SemaphoreLockManager;
import net.sf.hajdbc.state.StateManager;
import net.sf.hajdbc.state.distributed.DistributedStateManager;
import net.sf.hajdbc.state.sync.H2RecoveryCommand;
import net.sf.hajdbc.state.sync.SyncMgrImpl;
import net.sf.hajdbc.sync.DumpRestoreSynchronizationStrategy;
import net.sf.hajdbc.sync.SynchronizationContext;
import org.h2.tools.Server;
import static org.mockito.Mockito.*;

/** 独立 JVM 的真实 TCP_NIO2 双通道/H2 接收器；仅替代业务集群和选举，不替代恢复、锁及文件传输。 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class H2RecoveryProcessHelper {
  private H2RecoveryProcessHelper() { }

  public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[0]); String id = args[1];
    Path local = root.resolve(id); Files.createDirectories(local);
    System.setProperty("ha-jdbc.h2-recovery-dir", local.resolve("recovery").toString());
    System.setProperty("ha-jdbc.sync.allowed-roots", root.toString());
    String firstHost = args.length > 8 ? args[8] : "127.0.0.1";
    String secondHost = args.length > 9 ? args[9] : "127.0.0.1";
    int ordinal = id.equals("source") ? 0 : 1;
    System.setProperty("jgroups.bind_addr", ordinal == 0 ? firstHost : secondHost);
    System.setProperty("jgroups.tcp.bind_port_state", args[2 + ordinal]);
    System.setProperty("jgroups.tcp.bind_port", args[4 + ordinal]);
    System.setProperty("jgroups.tcpping.initial_hosts_state", hosts(firstHost, args[2], secondHost, args[3]));
    System.setProperty("jgroups.tcpping.initial_hosts", hosts(firstHost, args[4], secondHost, args[5]));
    // 只缩短测试的失败发现时间，不改生产 XML 或全局协议默认值。
    System.setProperty("jgroups.fd_all.timeout", "2500");
    System.setProperty("jgroups.fd_all.interval", "500");
    System.setProperty("jgroups.verify_suspect.timeout", "500");
    Server server = args.length > 10 && args[10].equals("external-h2") ? null
        : Server.createTcpServer("-tcpPort", args[6 + ordinal], "-tcpAllowOthers", "-ifNotExists").start();
    CommandDispatcher dispatcher = null; DistributedLockManager locks = null; H2RecoveryRuntime runtime = null;
    try {
      Database source = database("source", url(root, "source", args[6]).replace("127.0.0.1", firstHost));
      Database target = database("target", url(root, "target", args[7]).replace("127.0.0.1", secondHost));
      Database mine = ordinal == 0 ? source : target;
      if (!Files.exists(local.resolve("database.mv.db"))) {
        sql(mine.getLocation(), ordinal == 0 ? "CREATE TABLE BUSINESS_DATA(ID INT)" : "CREATE TABLE OLD_DATA(ID INT)");
        sql(mine.getLocation(), ordinal == 0 ? "INSERT INTO BUSINESS_DATA VALUES(42)" : "INSERT INTO OLD_DATA VALUES(9)");
      }
      DatabaseCluster cluster = mock(DatabaseCluster.class);
      DistributedStateManager state = mock(DistributedStateManager.class);
      Decoder decoder = mock(Decoder.class);
      Map<String, Object> contexts = new ConcurrentHashMap<>();
      when(cluster.getId()).thenReturn("recovery-process-test");
      when(cluster.getLocalDatabase()).thenReturn(mine);
      when(cluster.getDatabase("source")).thenReturn(source); when(cluster.getDatabase("target")).thenReturn(target);
      when(cluster.getDecoder()).thenReturn(decoder); when(cluster.getDistributedManager()).thenReturn(state);
      when(source.decodePassword(decoder)).thenReturn(""); when(target.decodePassword(decoder)).thenReturn("");
      when(state.getDatabaseCluster()).thenReturn(cluster);
      when(state.getLocalStateManager()).thenReturn(mock(StateManager.class));
      when(state.getExtContext(anyString())).thenAnswer(call -> contexts.get(call.getArguments()[0]));
      doAnswer(call -> { contexts.put((String) call.getArguments()[0], call.getArguments()[1]); return null; })
          .when(state).setExtContext(anyString(), any());
      boolean[] failImport = {false};
      H2Dialect dialect = new H2Dialect() {
        @Override public void dump(SynchronizationContext context, Database database, Decoder codec, java.io.File file, boolean dataOnly) throws Exception {
          super.dump(context, database, codec, file, dataOnly);
          if (failImport[0]) {
            Files.write(file.toPath(), "\nINVALID_RESTORE_TEST_STATEMENT;\n".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
            net.sf.hajdbc.util.ZipUtils.compressFile(file.getPath(), file.getPath() + ".zip");
          }
        }
      };
      when(cluster.getDialect()).thenReturn(dialect);
      SemaphoreLockManager raw = new SemaphoreLockManager(false); when(cluster.getLockManager()).thenReturn(raw);
      JGroupsCommandDispatcherFactory factory = new JGroupsCommandDispatcherFactory();
      factory.setStack("tcp.xml"); factory.setName(id); factory.setTimeout(6000);
      locks = new DistributedLockManager(cluster, factory); when(cluster.getLockManager()).thenReturn(locks);
      dispatcher = factory.createCommandDispatcher("recovery-process-test.state", state, mock(Stateful.class), mock(MembershipListener.class));
      CommandDispatcher channel = dispatcher;
      when(state.getDispatcher()).thenReturn(channel);
      when(state.executeAll(any())).thenAnswer(call -> channel.executeAll((Command) call.getArguments()[0]));
      when(state.execute(any(), any(Member.class))).thenAnswer(call -> channel.execute((Command) call.getArguments()[0], (Member) call.getArguments()[1]));
      when(state.getMember(anyString())).thenAnswer(call -> {
        for (Object value : channel.membershipSnapshot().getMembers()) {
          AddressMember member = (AddressMember) value;
          if (call.getArguments()[0].equals(org.jgroups.util.UUID.get(member.getAddress()))) return member;
        }
        return null;
      });
      when(state.getSyncMgr()).thenReturn(new SyncMgrImpl(state));
      locks.start(); runtime = H2RecoveryRuntime.install(state, Arrays.asList(source, target)); dispatcher.start();
      H2RecoveryCoordinator coordinator = (H2RecoveryCoordinator) contexts.get(H2RecoveryCoordinator.class.getName());
      H2RecoveryRuntime installed = runtime;
      write(local.resolve("ready"), "READY");
      try (BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
        for (String line; (line = commands.readLine()) != null;) {
          String[] request = line.split(" ", 2); String result;
          try {
            switch (request[1]) {
              case "DISCOVER": coordinator.discover(); result = "OK"; break;
              case "STATUS":
                result = "eligible=" + coordinator.isEligible("target") + ",state=" + dispatcher.membershipSnapshot().getMembers().size()
                    + ",lock=" + locks.membershipSnapshot().getMembers().size(); break;
              case "SYNC": case "FAIL_IMPORT":
                failImport[0] = request[1].equals("FAIL_IMPORT");
                coordinator.synchronize("target", () -> {
                  SynchronizationContext context = mock(SynchronizationContext.class);
                  when(context.getDatabaseCluster()).thenReturn(cluster); when(context.getSourceDatabase()).thenReturn(source);
                  when(context.getTargetDatabase()).thenReturn(target); when(context.getDecoder()).thenReturn(decoder);
                  when(context.getDialect()).thenReturn(dialect); new DumpRestoreSynchronizationStrategy().synchronize(context);
                }); result = "OK"; break;
              case "RECONCILE": installed.onHealthRound(); result = "OK"; break;
              case "ROWS":
                try (Connection connection = DriverManager.getConnection(mine.getLocation(), "sa", "");
                    Statement statement = connection.createStatement(); java.sql.ResultSet rows = statement.executeQuery("SELECT ID FROM BUSINESS_DATA ORDER BY ID")) {
                  StringBuilder values = new StringBuilder(); while (rows.next()) values.append(rows.getInt(1)).append(','); result = values.toString();
                }
                break;
              case "CRASH": Runtime.getRuntime().halt(23); throw new AssertionError("halt returned");
              case "STOP": write(local.resolve(request[0]), "OK"); return;
              default: throw new IllegalArgumentException("Unknown test command");
            }
          } catch (Exception failure) { failure.printStackTrace(); result = "ERROR:" + failure.getClass().getSimpleName(); }
          write(local.resolve(request[0]), result);
        }
      }
    } finally {
      if (runtime != null) runtime.stop();
      if (dispatcher != null) dispatcher.stop();
      if (locks != null) locks.stop(); if (server != null) server.stop();
    }
  }

  private static Database database(String id, String location) {
    Database database = mock(Database.class); when(database.getId()).thenReturn(id); when(database.getIp()).thenReturn(id);
    when(database.getLocation()).thenReturn(location); when(database.getUser()).thenReturn("sa"); return database;
  }
  private static String hosts(String firstHost, String first, String secondHost, String second) {
    return firstHost + "[" + first + "]," + secondHost + "[" + second + "]";
  }
  static String url(Path root, String id, String port) {
    return "jdbc:h2:tcp://127.0.0.1:" + port + "/" + root.resolve(id).resolve("database").toString().replace('\\', '/');
  }
  private static void sql(String location, String text) throws Exception {
    try (Connection connection = DriverManager.getConnection(location, "sa", ""); Statement statement = connection.createStatement()) { statement.execute(text); }
  }
  private static void write(Path path, String value) throws Exception { Files.write(path, value.getBytes(StandardCharsets.UTF_8)); }
}
