package net.sf.hajdbc.dialect.h2;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.codec.Decoder;
import net.sf.hajdbc.distributed.CommandDispatcher;
import net.sf.hajdbc.distributed.CommandDispatcherFactory;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.distributed.MembershipSnapshot;
import net.sf.hajdbc.distributed.jgroups.AddressMember;
import net.sf.hajdbc.lock.distributed.DistributedLockManager;
import net.sf.hajdbc.lock.semaphore.SemaphoreLockManager;
import net.sf.hajdbc.state.StateManager;
import net.sf.hajdbc.state.distributed.DistributedStateManager;
import net.sf.hajdbc.state.sync.H2RecoveryCommand;
import net.sf.hajdbc.state.sync.SyncMgr;
import net.sf.hajdbc.sync.DumpRestoreSynchronizationStrategy;
import net.sf.hajdbc.sync.SynchronizationContext;
import org.h2.tools.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 两份独立记录、两套真实写锁与 H2 TCP 库；消息逐次序列化，传输时序可精确控制。 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class H2RecoveryCoordinatorTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static final String SHA = String.join("", java.util.Collections.nCopies(64, "a"));
  private final Map<String, String> identities = new LinkedHashMap<>();
  private final Map<Member, Node> nodes = new LinkedHashMap<>();
  private final AtomicInteger restoreCalls = new AtomicInteger();
  private Node source, target;
  private Database sourceDb, targetDb;
  private Decoder decoder;
  private Server server;
  private String sourceUrl, targetUrl, oldRoots;
  private MembershipSnapshot stateView, lockView;
  private boolean missingPrepareReply, missingDecisionReply, unsupported, asynchronousRestore, skipTargetBind, failAfterRestore;
  private java.util.concurrent.ExecutorService executor;
  private java.util.concurrent.Future<H2RecoveryCommand.Reply> physical;
  private H2RecoveryCommand lastRestore;
  private static volatile boolean pause;
  private static volatile CountDownLatch entered, proceed;

  @Before public void setup() throws Exception {
    oldRoots = System.getProperty("ha-jdbc.sync.allowed-roots");
    System.setProperty("ha-jdbc.sync.allowed-roots", temporary.getRoot().getPath());
    identities.put("source", SHA); identities.put("target", SHA); identities.put("offline", SHA);
    source = new Node("source"); target = new Node("target");
    nodes.put(source.member, source); nodes.put(target.member, target);
    stateView = new MembershipSnapshot(source.member, 1, nodes.keySet());
    lockView = new MembershipSnapshot(source.lockMember, 1, Arrays.asList(source.lockMember, target.lockMember));
    source.start(); target.start();
    server = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
    sourceUrl = url("source-db"); targetUrl = url("target-db");
    sourceDb = database("source", sourceUrl); targetDb = database("target", targetUrl);
    decoder = mock(Decoder.class);
    for (Node node : nodes.values()) {
      when(node.cluster.getDatabase("source")).thenReturn(sourceDb);
      when(node.cluster.getDatabase("target")).thenReturn(targetDb);
      when(node.cluster.getDecoder()).thenReturn(decoder);
      when(node.cluster.getDistributedManager()).thenReturn(node.state);
      when(node.state.getExtContext(H2RecoveryCoordinator.class.getName())).thenReturn(node.coordinator);
    }
    when(sourceDb.decodePassword(decoder)).thenReturn(""); when(targetDb.decodePassword(decoder)).thenReturn("");
    entered = new CountDownLatch(1); proceed = new CountDownLatch(1); pause = false;
    execute(sourceUrl, "CREATE ALIAS RECOVERY_GATE FOR \"" + getClass().getName() + ".gate\"",
        "CREATE TABLE BUSINESS_DATA(ID INT CHECK (RECOVERY_GATE() = 1))", "INSERT INTO BUSINESS_DATA VALUES(42)");
    execute(targetUrl, "CREATE TABLE OLD_DATA(ID INT)", "INSERT INTO OLD_DATA VALUES(9)");
    source.coordinator.discover(); target.coordinator.discover();
  }

  @After public void cleanup() throws Exception {
    pause = false; if (proceed != null) proceed.countDown();
    if (executor != null) { executor.shutdownNow(); assertTrue("Physical test task leaked", executor.awaitTermination(15, TimeUnit.SECONDS)); }
    try {
      if (source != null && source.store != null) source.store.close();
      if (target != null && target.store != null) target.store.close();
    } finally {
      if (server != null) server.stop();
      if (oldRoots == null) System.clearProperty("ha-jdbc.sync.allowed-roots");
      else System.setProperty("ha-jdbc.sync.allowed-roots", oldRoots);
    }
  }

  private String url(String name) { return "jdbc:h2:tcp://localhost:" + server.getPort() + "/"
      + new File(temporary.getRoot(), name).getAbsolutePath().replace('\\', '/'); }
  private Database database(String id, String url) {
    Database database = mock(Database.class); when(database.getId()).thenReturn(id);
    when(database.getLocation()).thenReturn(url); when(database.getUser()).thenReturn("sa"); return database;
  }
  private static void execute(String url, String... sql) throws Exception {
    try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement statement = connection.createStatement()) {
      for (String text : sql) statement.execute(text);
    }
  }
  private void synchronize() throws Exception {
    source.coordinator.synchronize("target", () -> {
      SynchronizationContext context = mock(SynchronizationContext.class);
      when(context.getDatabaseCluster()).thenReturn(source.cluster);
      when(context.getSourceDatabase()).thenReturn(sourceDb); when(context.getTargetDatabase()).thenReturn(targetDb);
      H2Dialect dialect = new H2Dialect() {
        @Override public void dump(SynchronizationContext context, Database database, Decoder decoder, File file, boolean dataOnly) throws Exception {
          super.dump(context, database, decoder, file, dataOnly);
          if (asynchronousRestore) {
            Files.write(file.toPath(), "\nCALL RECOVERY_GATE();\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
            net.sf.hajdbc.util.ZipUtils.compressFile(file.getPath(), file.getPath() + ".zip");
          }
        }
      };
      when(context.getDecoder()).thenReturn(decoder); when(context.getDialect()).thenReturn(dialect);
      new DumpRestoreSynchronizationStrategy().synchronize(context);
      if (failAfterRestore) throw new IOException("Post-restore callback rejected the operation");
    });
    assertNull(H2RecoveryCoordinator.current(source.cluster));
  }
  private void expectFailure() throws Exception { try { synchronize(); fail("Unsafe restore succeeded"); } catch (Exception expected) { } }

  @Test(timeout = 30000) public void realStrategyPreparesBothMembersAndOnlyCommitMakesTargetEligible() throws Exception {
    assertFalse(target.coordinator.isEligible("target")); synchronize();
    assertEquals(1, restoreCalls.get());
    for (Node node : nodes.values()) {
      H2ActivationIntent.Snapshot record = node.store.target("target").intent.snapshot();
      assertEquals(H2ActivationIntent.Decision.COMMIT, record.decision); assertTrue(record.finished); assertTrue(record.allApplied);
      assertEquals(new java.util.HashSet<>(Arrays.asList("source", "target")), record.databases);
      verify(node.cluster, times(1)).activate(targetDb, node.localState);
      assertTrue(node.raw.readLock(null).tryLock()); node.raw.readLock(null).unlock();
    }
    assertTrue(target.coordinator.isEligible("target")); assertEquals(1, target.store.target("target").journal.getGeneration());
    assertRows(targetUrl, 42);
  }

  @Test(timeout = 30000) public void unrelatedConfiguredOfflineNodeDoesNotBlockAutomaticRestore() throws Exception {
    assertEquals(3, source.store.databases().size()); assertEquals(2, nodes.size());
    synchronize(); assertEquals(1, restoreCalls.get()); assertTrue(target.coordinator.isEligible("target"));
    assertFalse(source.store.target("target").intent.snapshot().databases.contains("offline"));
  }

  @Test(timeout = 35000) public void completedParticipantCanLeaveWithoutBlockingTheNextOperation() throws Exception {
    Node third = new Node("offline");
    nodes.put(third.member, third);
    stateView = new MembershipSnapshot(source.member, 2, nodes.keySet());
    lockView = new MembershipSnapshot(source.lockMember, 2, Arrays.asList(source.lockMember, target.lockMember, third.lockMember));
    third.start();
    try {
      synchronize(); assertTrue(source.store.target("target").intent.snapshot().allApplied);
      assertEquals(3, third.store.target("target").intent.snapshot().databases.size());
      nodes.remove(third.member);
      stateView = new MembershipSnapshot(source.member, 3, nodes.keySet());
      lockView = new MembershipSnapshot(source.lockMember, 3, Arrays.asList(source.lockMember, target.lockMember));
      synchronize(); assertEquals(2, restoreCalls.get()); assertTrue(target.coordinator.isEligible("target"));
      nodes.put(third.member, third);
      stateView = new MembershipSnapshot(source.member, 4, nodes.keySet());
      lockView = new MembershipSnapshot(source.lockMember, 4, Arrays.asList(source.lockMember, target.lockMember, third.lockMember));
      third.coordinator.discover();
      assertEquals(2, third.store.target("target").intent.snapshot().epoch);
      assertTrue(third.store.target("target").intent.snapshot().allApplied);
      assertEquals("Rejoining a completed member must not run SQL again", 2, restoreCalls.get());
    } finally {
      nodes.remove(third.member); third.store.close();
    }
  }

  @Test(timeout = 30000) public void unknownCompletedRestoreDoesNotResendSqlAndNextOperationCanRecoverAutomatically() throws Exception {
    missingDecisionReply = true; expectFailure(); assertEquals(1, restoreCalls.get());
    for (Node node : nodes.values()) assertEquals(H2ActivationIntent.Decision.COMMIT, node.store.target("target").intent.snapshot().decision);
    missingDecisionReply = false; source.coordinator.reconcile();
    assertEquals(1, restoreCalls.get()); assertTrue(target.coordinator.isEligible("target"));
    execute(sourceUrl, "INSERT INTO BUSINESS_DATA VALUES(43)"); synchronize();
    assertEquals(2, restoreCalls.get()); assertEquals(2, target.store.target("target").journal.getGeneration()); assertRows(targetUrl, 42, 43);
  }

  @Test(timeout = 45000) public void timeoutWhileSqlStillRunsCannotStartAnotherDropAndAutomaticallyRecoversAfterAbort() throws Exception {
    pause = true; asynchronousRestore = true; executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    expectFailure(); assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(1, restoreCalls.get());
    assertFalse(physical.isDone()); assertTrue(target.store.target("target").journal.isRunning());
    expectFailure(); assertEquals("No second RESTORE while the old SQL is running", 1, restoreCalls.get());
    assertFalse(target.raw.readLock(null).tryLock());
    pause = false; proceed.countDown(); physical.get(10, TimeUnit.SECONDS);
    source.coordinator.reconcile();
    assertEquals(H2ActivationIntent.Decision.ABORT, target.store.target("target").intent.snapshot().decision);
    assertFalse(target.coordinator.isEligible("target")); assertEquals(1, restoreCalls.get());
    asynchronousRestore = false; synchronize();
    assertEquals(2, restoreCalls.get()); assertTrue(target.coordinator.isEligible("target")); assertRows(targetUrl, 42);
  }

  @Test(timeout = 30000) public void missingPrepareAcknowledgementAbortsWithoutExportOrDrop() throws Exception {
    missingPrepareReply = true; expectFailure(); assertEquals(0, restoreCalls.get());
    for (Node node : nodes.values()) {
      assertEquals(H2ActivationIntent.Decision.ABORT, node.store.target("target").intent.snapshot().decision);
      assertTrue(node.raw.readLock(null).tryLock()); node.raw.readLock(null).unlock();
    }
    verify(source.cluster, never()).activate(any(), any());
    try (java.util.stream.Stream<Path> files = Files.list(source.store.inputPath("target", "unused").getParent())) { assertEquals(0, files.count()); }
    missingPrepareReply = false; synchronize(); assertEquals(1, restoreCalls.get());
  }

  @Test(timeout = 30000) public void lateRestoreAndOldCommitCannotAffectANewerSuccessfulOperation() throws Exception {
    synchronize(); H2RecoveryCommand oldRestore = lastRestore;
    H2ActivationIntent.Snapshot oldCommit = source.store.target("target").intent.snapshot();
    synchronize();
    assertEquals(H2RecoveryCommand.Status.FAILED, target.participant.handle(oldRestore).status);
    assertEquals(H2RecoveryCommand.Status.FAILED, target.participant.handle(H2RecoveryCommand.apply("target", SHA, oldCommit)).status);
    assertEquals(2, target.store.target("target").journal.getGeneration());
    assertTrue(target.coordinator.isEligible("target")); assertEquals(2, restoreCalls.get());
  }

  @Test(timeout = 20000) public void unsupportedMemberCannotReachPrepareOrDestructiveSql() throws Exception {
    unsupported = true; expectFailure(); assertEquals(0, restoreCalls.get());
    assertEquals(H2ActivationIntent.Decision.NONE, source.store.target("target").intent.snapshot().decision);
    assertEquals(H2ActivationIntent.Decision.NONE, target.store.target("target").intent.snapshot().decision);
  }

  @Test(timeout = 30000) public void partialInputBindingAbortsTheUnboundParticipantWithoutDropping() throws Exception {
    skipTargetBind = true; expectFailure(); assertEquals(0, restoreCalls.get());
    H2ActivationIntent.Snapshot left = source.store.target("target").intent.snapshot(), right = target.store.target("target").intent.snapshot();
    assertEquals(H2ActivationIntent.Decision.ABORT, left.decision); assertEquals(left.decision, right.decision);
    assertEquals(64, left.digest.length()); assertEquals(left.digest, right.digest); assertTrue(right.finished);
    skipTargetBind = false; synchronize(); assertEquals(1, restoreCalls.get()); assertRows(targetUrl, 42);
  }

  @Test(timeout = 90000) public void repeatedFailuresKeepTheFirstPreDropBackupUntilAnAutomaticCommitSucceeds() throws Exception {
    failAfterRestore = true;
    Path first = null;
    for (int attempt = 0; attempt < 12; attempt++) {
      expectFailure();
      Path pinned = java.nio.file.Paths.get(target.store.target("target").journal.getPinnedBackup());
      if (first == null) first = pinned;
      assertEquals(first, pinned); assertTrue(Files.exists(first));
      assertTrue(new String(Files.readAllBytes(first), java.nio.charset.StandardCharsets.UTF_8).contains("OLD_DATA"));
      assertFalse(target.coordinator.isEligible("target"));
    }
    assertEquals(12, restoreCalls.get());
    failAfterRestore = false; synchronize(); assertTrue(target.coordinator.isEligible("target"));
    assertEquals("", target.store.target("target").journal.getPinnedBackup());
    assertTrue(Files.exists(first)); assertRows(targetUrl, 42);
  }

  private static void assertRows(String url, int... expected) throws Exception {
    try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement statement = connection.createStatement();
        java.sql.ResultSet rows = statement.executeQuery("SELECT ID FROM BUSINESS_DATA ORDER BY ID")) {
      for (int id : expected) { assertTrue(rows.next()); assertEquals(id, rows.getInt(1)); } assertFalse(rows.next());
    }
  }

  /** 只用于测试服务端约束检查暂停，源端初始化时保持关闭。 */
  public static int gate() throws InterruptedException {
    if (pause) { entered.countDown(); if (!proceed.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate was not released"); }
    return 1;
  }

  private final class Node {
    final String id;
    final Member member = new AddressMember(org.jgroups.util.UUID.randomUUID());
    final Member lockMember = new AddressMember(org.jgroups.util.UUID.randomUUID());
    final DatabaseCluster cluster = mock(DatabaseCluster.class);
    final DistributedStateManager state = mock(DistributedStateManager.class);
    final StateManager localState = mock(StateManager.class);
    final SemaphoreLockManager raw = new SemaphoreLockManager(false);
    H2RecoveryStore store; H2RecoveryParticipant participant; H2RecoveryCoordinator coordinator;
    Node(String id) { this.id = id; }
    void start() throws Exception {
      when(cluster.getDatabase("source")).thenAnswer(invocation -> sourceDb);
      when(cluster.getDatabase("target")).thenAnswer(invocation -> targetDb);
      when(cluster.getDecoder()).thenAnswer(invocation -> decoder);
      when(cluster.getDistributedManager()).thenReturn(state);
      when(state.getExtContext(H2RecoveryCoordinator.class.getName())).thenAnswer(invocation -> coordinator);
      Path directory = temporary.getRoot().toPath().resolve(id);
      H2RecoveryStore.initialize(directory, "cluster", id, identities, java.util.Collections.singleton("source"));
      store = new H2RecoveryStore(directory, "cluster", id, identities);
      CommandDispatcher dispatcher = mock(CommandDispatcher.class), lockDispatcher = mock(CommandDispatcher.class);
      when(dispatcher.getLocal()).thenReturn(member); when(lockDispatcher.getLocal()).thenReturn(lockMember);
      when(dispatcher.membershipSnapshot()).thenAnswer(invocation -> stateView);
      when(lockDispatcher.membershipSnapshot()).thenAnswer(invocation -> lockView);
      when(state.getDispatcher()).thenReturn(dispatcher); when(state.getDatabaseCluster()).thenReturn(cluster);
      when(state.getLocalStateManager()).thenReturn(localState); when(cluster.getLockManager()).thenReturn(raw);
      CommandDispatcherFactory factory = mock(CommandDispatcherFactory.class);
      when(factory.createCommandDispatcher(anyString(), any(), any(), any())).thenReturn(lockDispatcher);
      DistributedLockManager locks = new DistributedLockManager(cluster, factory);
      participant = new H2RecoveryParticipant(store, state, locks, id); participant.restoreWriteFences();
      coordinator = new H2RecoveryCoordinator(store, participant, state, locks);
      when(state.executeAll(any())).thenAnswer(invocation -> {
        H2RecoveryCommand command = (H2RecoveryCommand) invocation.getArguments()[0];
        Map<Member, H2RecoveryCommand.Reply> result = new LinkedHashMap<>();
        for (Node node : nodes.values()) {
          if (skipTargetBind && command.action == H2RecoveryCommand.Action.BIND_INPUT && node == target) {
            result.put(node.member, null); continue;
          }
          H2RecoveryCommand copy = net.sf.hajdbc.util.Objects.deserialize(net.sf.hajdbc.util.Objects.serialize(command));
          H2RecoveryCommand.Reply reply = unsupported && node == target ? H2RecoveryCommand.Reply.unsupported(command.target, command.targetIdentity)
              : node.participant.handle(copy);
          result.put(node.member, (missingPrepareReply && command.action == H2RecoveryCommand.Action.PREPARE && node == target)
              || (missingDecisionReply && command.action == H2RecoveryCommand.Action.APPLY_DECISION && node == target) ? null : reply);
        }
        return result;
      });
      SyncMgr sync = mock(SyncMgr.class); when(state.getSyncMgr()).thenReturn(sync);
      when(sync.upload(any(), any(File.class), anyString())).thenAnswer(invocation -> {
        File file = (File) invocation.getArguments()[1]; String path = (String) invocation.getArguments()[2];
        Files.copy(file.toPath(), java.nio.file.Paths.get(path)); return true;
      });
      when(sync.executeOnce(any(), any())).thenAnswer(invocation -> {
        H2RecoveryCommand command = (H2RecoveryCommand) invocation.getArguments()[1]; restoreCalls.incrementAndGet(); lastRestore = command;
        H2RecoveryCommand copy = net.sf.hajdbc.util.Objects.deserialize(net.sf.hajdbc.util.Objects.serialize(command));
        if (asynchronousRestore) {
          physical = executor.submit(() -> target.participant.handle(copy));
          assertTrue("The SQL must really be running before reporting an unknown RPC result", entered.await(10, TimeUnit.SECONDS));
          return null;
        }
        return target.participant.handle(copy);
      });
    }
  }
}
