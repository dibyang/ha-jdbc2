package net.sf.hajdbc.dialect.h2;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.h2.tools.Server;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 验证真实 H2 服务端独占跨 DROP 保持，并覆盖恢复客户端 JVM 被杀而服务端继续导入。 */
public class H2RestoreExclusiveTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static volatile CountDownLatch entered;
  private static volatile CountDownLatch proceed;
  private static volatile CountDownLatch completed;

  /** 仅用于测试脚本在服务端可控暂停，不依靠随机延时猜测导入已开始。 */
  public static int pauseImport() throws InterruptedException {
    entered.countDown();
    if (!proceed.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test import was not released");
    return 1;
  }

  /** 脚本末尾确认真实 INSERT 已执行，仍需等待 TCP 会话实际清理。 */
  public static int importComplete() { completed.countDown(); return 1; }

  @Test(timeout = 35000) public void killingClientDoesNotAdmitAnotherRestoreWhileServerScriptRuns() throws Exception {
    String name = "exclusive_" + UUID.randomUUID().toString().replace("-", "");
    entered = new CountDownLatch(1);
    proceed = new CountDownLatch(1);
    completed = new CountDownLatch(1);
    Server server = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
    Process child = null;
    try (Connection observer = DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "")) {
      try (Statement statement = observer.createStatement()) { statement.execute("CREATE TABLE OLD_DATA(ID INT)"); }
      String url = "jdbc:h2:tcp://localhost:" + server.getPort() + "/mem:" + name;
      Path script = temporary.getRoot().toPath().resolve("restore.sql");
      String owner = H2RestoreExclusiveTest.class.getName();
      Files.write(script, ("CREATE TABLE RESTORED(ID INT);\n"
          + "CREATE ALIAS PAUSE_IMPORT FOR \"" + owner + ".pauseImport\";\n"
          + "CALL PAUSE_IMPORT();\nINSERT INTO RESTORED VALUES(7);\n"
          + "CREATE ALIAS IMPORT_COMPLETE FOR \"" + owner + ".importComplete\";\nCALL IMPORT_COMPLETE();\n")
          .getBytes(StandardCharsets.UTF_8));
      String executable = new File(new File(System.getProperty("java.home"), "bin"),
          System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").getPath();
      // H2DB 分发包也有运行依赖，子进程沿用实际测试运行类路径，不假设单一 H2 JAR 自包含。
      String classpath = System.getProperty("java.class.path");
      Path clientLog = temporary.newFile("client-" + name + ".log").toPath();
      child = new ProcessBuilder(executable, "-cp", classpath, Client.class.getName(), url, script.toString())
          .redirectErrorStream(true).redirectOutput(clientLog.toFile()).start();
      boolean executing = entered.await(10, TimeUnit.SECONDS);
      assertTrue("Script must really be inside H2 server; child output="
          + new String(Files.readAllBytes(clientLog), StandardCharsets.UTF_8), executing);
      org.h2.engine.SessionLocal observerSession = (org.h2.engine.SessionLocal) ((org.h2.jdbc.JdbcConnection) observer).getSession();
      org.h2.engine.Database nativeDatabase = observerSession.getDatabase();
      org.h2.engine.SessionLocal exclusiveOwner = nativeDatabase.getExclusiveSession();
      assertNotNull("The server itself must report an exclusive owner", exclusiveOwner);
      assertNotSame(observerSession, exclusiveOwner);
      assertExclusive(url, nativeDatabase, exclusiveOwner);
      child.destroyForcibly();
      assertTrue("Client JVM must be reaped", child.waitFor(5, TimeUnit.SECONDS));
      // 服务端仍卡在同一脚本，客户端文件锁消失不能使第二个恢复进入数据库。
      assertExclusive(url, nativeDatabase, exclusiveOwner);
      proceed.countDown();
      assertTrue("Existing server script must finish", completed.await(5, TimeUnit.SECONDS));
      try (Connection restored = connectAfterExclusiveEnds(url, nativeDatabase, exclusiveOwner);
           Statement statement = restored.createStatement();
           java.sql.ResultSet rows = statement.executeQuery("SELECT ID FROM RESTORED")) {
        assertTrue(rows.next());
        assertEquals(7, rows.getInt(1));
        assertFalse(rows.next());
      }
    } finally {
      proceed.countDown();
      if (child != null) {
        if (child.isAlive()) child.destroyForcibly();
        assertTrue(child.waitFor(5, TimeUnit.SECONDS));
      }
      server.stop();
      try (Connection cleanup = DriverManager.getConnection("jdbc:h2:mem:" + name, "sa", "");
           Statement statement = cleanup.createStatement()) { statement.execute("SHUTDOWN"); }
    }
  }

  private static void assertExclusive(String url, org.h2.engine.Database database, org.h2.engine.SessionLocal owner) throws Exception {
    assertSame("Server ownership must not disappear with the client", owner, database.getExclusiveSession());
    assertFalse(owner.isClosed());
    assertNotNull("The original server command must still be executing", owner.getCurrentCommand());
    try (Connection unexpected = DriverManager.getConnection(url, "sa", "")) {
      fail("A second restore connection entered an exclusively owned database");
    } catch (SQLException expected) {
      if (expected.getErrorCode() != 90135 && expected.getErrorCode() != 90067) {
        throw new AssertionError("Expected H2 exclusive rejection, received SQLState="
            + expected.getSQLState() + ", errorCode=" + expected.getErrorCode()
            + ", message=" + expected.getMessage(), expected);
      }
      // TCP 拒绝也可能先表现为连接关闭，必须由上/下两侧服务端断言证明独占仍由原会话持有。
      if (expected.getErrorCode() == 90067) System.out.println("H2 rejected new TCP session while original exclusive owner remains: " + expected);
    }
    assertSame(owner, database.getExclusiveSession());
    assertFalse(owner.isClosed());
  }

  @Test(timeout = 100000) public void repeatedClientCrashesKeepTheOriginalServerExclusiveOwner() throws Exception {
    for (int round = 0; round < 10; round++) killingClientDoesNotAdmitAnotherRestoreWhileServerScriptRuns();
  }

  private static Connection connectAfterExclusiveEnds(String url, org.h2.engine.Database database,
      org.h2.engine.SessionLocal owner) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (database.getExclusiveSession() == owner && System.nanoTime() < deadline) {
      // 门闩仅证明 INSERT 已执行，不证明 TCP finally 已完成；以服务端所有权消失为准。
      Thread.sleep(20);
    }
    assertNull("The original TCP session must actually release exclusive ownership", database.getExclusiveSession());
    assertTrue("The original server session must be closed", owner.isClosed());
    return DriverManager.getConnection(url, "sa", "");
  }

  @Test(timeout = 15000) public void autoReconnectCannotReplaceExclusiveSessionWhenAutoCommitIsDisabled() throws Exception {
    Server server = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
    String name = "no_reconnect_" + UUID.randomUUID().toString().replace("-", "");
    String url = "jdbc:h2:tcp://localhost:" + server.getPort() + "/mem:" + name + ";AUTO_RECONNECT=TRUE";
    try (Connection connection = DriverManager.getConnection(url, "sa", "");
         Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("SET EXCLUSIVE 1");
      statement.execute("CREATE TABLE PROTECTED_DATA(ID INT)");
      assertFalse(connection.getAutoCommit());
      statement.execute("SHUTDOWN");
      try { statement.executeQuery("SELECT 1"); fail("Recovery connection silently reconnected"); }
      catch (SQLException expected) { }
    } finally { server.stop(); }
  }

  @Test(timeout = 20000) public void restoreOutlivesConfiguredSocketTimeoutAndReleasesExclusiveOwner() throws Exception {
    restoreOutlivesSocketTimeout(false);
  }

  @Test(timeout = 20000) public void failedRestoreOutlivesConfiguredSocketTimeoutAndReleasesExclusiveOwner() throws Exception {
    restoreOutlivesSocketTimeout(true);
  }

  /** 真实 TCP 读超时不能先丢失拥有独占的会话；主调超时与物理收尾必须分开。 */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private void restoreOutlivesSocketTimeout(boolean failImport) throws Exception {
    entered = new CountDownLatch(1);
    proceed = new CountDownLatch(1);
    String name = "socket_timeout_" + UUID.randomUUID().toString().replace("-", "");
    Server server = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
    java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    try (Connection observer = DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "")) {
      observer.createStatement().execute("CREATE TABLE OLD_DATA(ID INT)");
      org.h2.engine.Database nativeDatabase = ((org.h2.engine.SessionLocal)
          ((org.h2.jdbc.JdbcConnection) observer).getSession()).getDatabase();
      String url = "jdbc:h2:tcp://localhost:" + server.getPort() + "/mem:" + name + ";NETWORK_TIMEOUT=250";
      Path input = temporary.newFile("socket-timeout.sql").toPath();
      Files.write(input, ("CREATE TABLE RESTORED(ID INT);\nCREATE ALIAS PAUSE_IMPORT FOR \""
          + H2RestoreExclusiveTest.class.getName() + ".pauseImport\";\nCALL PAUSE_IMPORT();\n"
          + (failImport ? "INVALID_IMPORT_SQL;\n" : "INSERT INTO RESTORED VALUES(7);\n")).getBytes(StandardCharsets.UTF_8));
      net.sf.hajdbc.Database database = org.mockito.Mockito.mock(net.sf.hajdbc.Database.class);
      net.sf.hajdbc.codec.Decoder decoder = org.mockito.Mockito.mock(net.sf.hajdbc.codec.Decoder.class);
      org.mockito.Mockito.when(database.getLocation()).thenReturn(url);
      org.mockito.Mockito.when(database.getUser()).thenReturn("sa");
      org.mockito.Mockito.when(database.decodePassword(decoder)).thenReturn("");
      DbRestore restore = new DbRestore() {
        @Override String getBakPath(String location) { return temporary.getRoot().toPath().resolve("old.sql").toString(); }
      };
      java.util.concurrent.Future<Boolean> task = worker.submit(() -> restore.restore(database, decoder, input.toFile(), true));
      try {
        assertTrue("Server must enter the real import", entered.await(5, TimeUnit.SECONDS));
        org.h2.engine.SessionLocal owner = nativeDatabase.getExclusiveSession();
        assertNotNull(owner);
        try {
          task.get(1, TimeUnit.SECONDS);
          fail("Restore abandoned the physical session at the configured socket timeout");
        } catch (java.util.concurrent.TimeoutException expected) { }
        assertSame(owner, nativeDatabase.getExclusiveSession());
        proceed.countDown();
        assertEquals(!failImport, task.get(5, TimeUnit.SECONDS));
        try (Connection next = connectAfterExclusiveEnds(url, nativeDatabase, owner)) {
          assertFalse(next.isClosed());
        }
      } finally {
        proceed.countDown();
        worker.shutdown();
        assertTrue("Restore worker must terminate", worker.awaitTermination(5, TimeUnit.SECONDS));
        // 失败实现可能泄漏原 socket；仅测试兜底关闭本测试库会话，防止污染后续用例。
        org.h2.engine.SessionLocal remaining = nativeDatabase.getExclusiveSession();
        if (remaining != null) remaining.close();
      }
    } finally {
      proceed.countDown();
      worker.shutdownNow();
      server.stop();
      try (Connection cleanup = DriverManager.getConnection("jdbc:h2:mem:" + name, "sa", "");
           Statement statement = cleanup.createStatement()) { statement.execute("SHUTDOWN"); }
    }
  }

  /** 独立客户端，服务端与 JUnit 仍留在父 JVM。 */
  public static class Client {
    public static void main(String[] args) throws Exception {
      Class.forName("org.h2.Driver");
      try (Connection connection = DriverManager.getConnection(args[0], "sa", "");
           Statement statement = connection.createStatement()) {
        connection.setAutoCommit(false);
        statement.execute("SET EXCLUSIVE 1");
        statement.execute("DROP ALL OBJECTS");
        statement.execute("RUNSCRIPT FROM '" + args[1].replace("'", "''") + "'");
        connection.commit();
      }
    }
  }
}
