package net.sf.hajdbc.dialect.h2;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 两个独立 JVM、真实状态/锁 TCP_NIO2 通道、真实 H2 文件库；不替代完整业务集群选举验收。 */
public class H2RecoveryJGroupsProcessTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final List<Node> processes = new ArrayList<>();
  private String[] ports;
  private int sequence;
  private org.h2.tools.Server server;

  @After public void cleanup() throws Exception {
    Throwable failure = null;
    for (Node node : processes) {
      try {
        try { node.writer.close(); } catch (Exception ignored) { }
        if (!node.process.waitFor(3, TimeUnit.SECONDS)) node.process.destroy();
        if (!node.process.waitFor(3, TimeUnit.SECONDS)) node.process.destroyForcibly();
        assertTrue("Test child JVM leaked: " + node.log, node.process.waitFor(5, TimeUnit.SECONDS));
        Path evidence = java.nio.file.Paths.get("build", "reports", "h2-jgroups-process"); Files.createDirectories(evidence);
        Files.copy(node.log, evidence.resolve(node.log.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (Exception | AssertionError cleanup) {
        if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
      }
    }
    if (server != null) server.stop();
    if (failure instanceof Exception) throw (Exception) failure;
    if (failure instanceof AssertionError) throw (AssertionError) failure;
  }

  @Test(timeout = 180000) public void realChannelsRestoreAndRestartCannotReopenAFailedTarget() throws Exception {
    ports = freePorts(6);
    // JDBC 服务与恢复客户端分离，真实覆盖客户端崩溃而 H2 服务仍运行的部署。
    server = org.h2.tools.Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
    ports[4] = ports[5] = Integer.toString(server.getPort());
    Node source = start("source"), target = start("target");
    awaitMembers(source); awaitMembers(target);
    assertEquals("OK", command(source, "DISCOVER")); assertEquals("OK", command(target, "DISCOVER"));
    assertEquals("OK", command(source, "SYNC")); assertRows(42);
    assertTrue(command(source, "STATUS").contains("eligible=true"));
    assertTrue(command(target, "STATUS").contains("eligible=true"));
    assertTrue(command(source, "FAIL_IMPORT").startsWith("ERROR:"));
    assertTrue(command(target, "STATUS").contains("eligible=false"));
    // 真正结束目标 JVM，再用同一文件与恢复目录启动；不重置代次或基线。
    target.process.destroyForcibly(); assertTrue(target.process.waitFor(8, TimeUnit.SECONDS));
    Node restarted = start("target"); awaitMembers(restarted); awaitMembers(source);
    assertEquals("OK", command(restarted, "DISCOVER"));
    assertTrue(command(restarted, "STATUS").contains("eligible=false"));
    assertEquals("OK", command(source, "DISCOVER"));
    assertEquals("OK", command(source, "SYNC")); assertRows(42);
    assertTrue(command(restarted, "STATUS").contains("eligible=true"));
    assertEquals("OK", command(source, "STOP")); assertEquals("OK", command(restarted, "STOP"));
    assertTrue(source.process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, source.process.exitValue());
    assertTrue(restarted.process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, restarted.process.exitValue());
  }

  private Node start(String id) throws Exception {
    Path root = temporary.getRoot().toPath(), local = root.resolve(id); Files.createDirectories(local); Files.deleteIfExists(local.resolve("ready"));
    String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    List<String> args = new ArrayList<>(); args.add(java.nio.file.Paths.get(System.getProperty("java.home"), "bin", executable).toString());
    args.add("-cp"); args.add(System.getProperty("java.class.path")); args.add(H2RecoveryProcessHelper.class.getName());
    args.add(root.toString()); args.add(id); java.util.Collections.addAll(args, ports);
    args.add("127.0.0.1"); args.add("127.0.0.1"); args.add("external-h2");
    Path log = root.resolve(id + "-" + processes.size() + ".log");
    Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    Node node = new Node(process, local, log); processes.add(node); awaitFile(node, local.resolve("ready"), 25000); return node;
  }
  private void awaitMembers(Node node) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    do { if (command(node, "STATUS").contains("state=2,lock=2")) return; Thread.sleep(50); } while (System.nanoTime() < end);
    fail("Two real JGroups views did not converge; log=" + node.log);
  }
  private String command(Node node, String command) throws Exception {
    String request = Integer.toString(++sequence); node.writer.write(request + " " + command + "\n"); node.writer.flush();
    Path response = node.directory.resolve(request); awaitFile(node, response, 25000);
    return new String(Files.readAllBytes(response), StandardCharsets.UTF_8);
  }
  private static void awaitFile(Node node, Path file, long millis) throws Exception {
    long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    while (!Files.exists(file)) {
      if (!node.process.isAlive()) fail("Test child exited: " + node.log);
      if (System.nanoTime() >= end) fail("Test child timed out: " + node.log + " response=" + file.getFileName());
      Thread.sleep(20);
    }
  }
  private void assertRows(int id) throws Exception {
    try (Connection connection = DriverManager.getConnection(H2RecoveryProcessHelper.url(temporary.getRoot().toPath(), "target", ports[5]), "sa", "");
        java.sql.Statement statement = connection.createStatement(); java.sql.ResultSet rows = statement.executeQuery("SELECT ID FROM BUSINESS_DATA")) {
      assertTrue(rows.next()); assertEquals(id, rows.getInt(1)); assertFalse(rows.next());
    }
  }
  private static String[] freePorts(int count) throws Exception {
    List<ServerSocket> reserved = new ArrayList<>(); String[] result = new String[count];
    try { for (int index = 0; index < count; index++) { ServerSocket socket = new ServerSocket(0); reserved.add(socket); result[index] = Integer.toString(socket.getLocalPort()); } }
    finally { for (ServerSocket socket : reserved) socket.close(); } return result;
  }
  private static final class Node {
    final Process process; final Path directory, log; final BufferedWriter writer;
    Node(Process process, Path directory, Path log) {
      this.process = process; this.directory = directory; this.log = log;
      this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    }
  }
}
