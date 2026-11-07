package net.sf.hajdbc.dialect.h2;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import net.sf.hajdbc.state.sync.H2RecoveryCommand.Operation;
import net.sf.hajdbc.state.sync.H2RecoveryCommand.Reply;
import net.sf.hajdbc.state.sync.H2RecoveryCommand.Status;
import org.h2.tools.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 接收器、真实持久记录、真实本地写锁和 H2 TCP 文件库一起运行；不连接现场。 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class H2RecoveryParticipantTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static final String SHA = String.join("", Collections.nCopies(64, "a"));
  private static volatile CountDownLatch entered, proceed;
  private static final java.util.concurrent.atomic.AtomicInteger imports = new java.util.concurrent.atomic.AtomicInteger();
  private final Member source = new AddressMember(org.jgroups.util.UUID.randomUUID());
  private final Member local = new AddressMember(org.jgroups.util.UUID.randomUUID());
  private final Member sourceLock = new AddressMember(org.jgroups.util.UUID.randomUUID());
  private final Member localLock = new AddressMember(org.jgroups.util.UUID.randomUUID());
  private MembershipSnapshot stateView, lockView;
  private CommandDispatcher dispatcher;
  private CommandDispatcher lockDispatcher;
  private DistributedStateManager state;
  private DatabaseCluster cluster;
  private Database database;
  private StateManager localState;
  private SemaphoreLockManager rawLocks;
  private H2RecoveryStore store;
  private H2RecoveryParticipant participant;
  private H2ActivationIntent sourceIntent;
  private Server server;
  private String url, oldRoots;
  private Path directory;
  private java.util.concurrent.ExecutorService executor;

  @org.junit.BeforeClass public static void recordActualH2Artifact() throws Exception {
    Path jar = java.nio.file.Paths.get(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    System.out.println("H2 regression artifact=" + jar.getFileName() + ", sha256=" + H2RecoveryParticipant.digest(jar));
  }

  @Before public void setup() throws Exception {
    oldRoots = System.getProperty("ha-jdbc.sync.allowed-roots");
    System.setProperty("ha-jdbc.sync.allowed-roots", temporary.getRoot().getPath());
    stateView = new MembershipSnapshot(source, 1, Arrays.asList(source, local));
    lockView = new MembershipSnapshot(sourceLock, 1, Arrays.asList(sourceLock, localLock));
    cluster = mock(DatabaseCluster.class); state = mock(DistributedStateManager.class);
    dispatcher = mock(CommandDispatcher.class); lockDispatcher = mock(CommandDispatcher.class);
    localState = mock(StateManager.class);
    when(state.getDatabaseCluster()).thenReturn(cluster); when(state.getDispatcher()).thenReturn(dispatcher);
    when(state.getLocalStateManager()).thenReturn(localState);
    when(dispatcher.getLocal()).thenReturn(local); when(lockDispatcher.getLocal()).thenReturn(localLock);
    when(dispatcher.membershipSnapshot()).thenAnswer(invocation -> stateView);
    when(lockDispatcher.membershipSnapshot()).thenAnswer(invocation -> lockView);
    directory = temporary.getRoot().toPath().resolve("recovery");
    Map<String, String> identities = new LinkedHashMap<>(); identities.put("source", SHA); identities.put("target", SHA);
    H2RecoveryStore.initialize(directory, "cluster", "target", identities, Collections.singleton("source"));
    store = new H2RecoveryStore(directory, "cluster", "target", identities);
    Path sourceRecord = temporary.getRoot().toPath().resolve("source.intent");
    H2ActivationIntent.create(sourceRecord, SHA); sourceIntent = new H2ActivationIntent(sourceRecord, SHA);
    createParticipant();
    server = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
    String file = new File(temporary.getRoot(), "database").getAbsolutePath().replace('\\', '/');
    url = "jdbc:h2:tcp://localhost:" + server.getPort() + "/" + file;
    database = mock(Database.class); Decoder decoder = mock(Decoder.class);
    when(cluster.getDatabase("target")).thenReturn(database); when(cluster.getDecoder()).thenReturn(decoder);
    when(database.getLocation()).thenReturn(url); when(database.getUser()).thenReturn("sa");
    when(database.decodePassword(decoder)).thenReturn("");
    try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE OLD_DATA(ID INT)"); statement.execute("INSERT INTO OLD_DATA VALUES(9)");
    }
    when(state.executeAll(any())).thenAnswer(invocation -> responses());
    entered = new CountDownLatch(1); proceed = new CountDownLatch(1); imports.set(0);
  }

  private void createParticipant() throws Exception {
    rawLocks = new SemaphoreLockManager(false); when(cluster.getLockManager()).thenReturn(rawLocks);
    CommandDispatcherFactory factory = mock(CommandDispatcherFactory.class);
    when(factory.createCommandDispatcher(anyString(), any(), any(), any())).thenReturn(lockDispatcher);
    participant = new H2RecoveryParticipant(store, state, new DistributedLockManager(cluster, factory), "target");
    participant.restoreWriteFences();
  }

  @After public void cleanup() throws Exception {
    proceed.countDown();
    if (executor != null) { executor.shutdownNow(); assertTrue("Test caller leaked", executor.awaitTermination(10, TimeUnit.SECONDS)); }
    try { if (store != null) store.close(); }
    finally {
      if (sourceIntent != null) sourceIntent.close();
      if (server != null) server.stop();
      if (oldRoots == null) System.clearProperty("ha-jdbc.sync.allowed-roots");
      else System.setProperty("ha-jdbc.sync.allowed-roots", oldRoots);
    }
  }

  private Operation operation() { return new Operation("target", SHA, "source", 1, "operation", 0, source, sourceLock, stateView, lockView); }
  private Path artifact(String script) throws Exception {
    Path zip = store.inputPath("target", "operation");
    Path input = java.nio.file.Paths.get(zip.toString().substring(0, zip.toString().length() - 4));
    Files.write(input, script.getBytes(StandardCharsets.UTF_8));
    net.sf.hajdbc.util.ZipUtils.compressFile(input.toString(), zip.toString()); Files.delete(input);
    return zip;
  }
  private H2RecoveryCommand prepare(Path artifact) throws Exception {
    Operation operation = operation(); String digest = H2RecoveryParticipant.digest(artifact);
    sourceIntent.prepare(1, "operation", operation.owner(), "source", operation.membership(), "", operation.participants(), operation.databases);
    assertEquals(Status.PREPARED, participant.handle(H2RecoveryCommand.prepare(operation)).status);
    assertFalse(rawLocks.readLock(null).tryLock());
    sourceIntent.bindInput(1, "operation", digest);
    assertEquals(Status.INPUT_BOUND, participant.handle(H2RecoveryCommand.bind(operation, digest)).status);
    return H2RecoveryCommand.restore(operation, digest, artifact.toString());
  }
  private Map<Member, Reply> responses() throws Exception {
    Map<Member, Reply> result = new LinkedHashMap<>();
    result.put(source, new Reply(Status.SUPPORTED, "target", SHA, "source", source, sourceLock,
        sourceIntent.snapshot(), 0, 0, false, stateView, lockView));
    result.put(local, participant.handle(H2RecoveryCommand.query("target", SHA)));
    return result;
  }

  @Test(timeout = 20000) public void realRestoreDuplicateAndCommitApplyOnceInTheProtectedOrder() throws Exception {
    H2RecoveryCommand command = prepare(artifact("CREATE TABLE RECOVERED(ID INT); INSERT INTO RECOVERED VALUES(11);"));
    assertEquals(Status.RESTORED, participant.handle(command).status);
    assertFalse(store.isEligible("target")); assertFalse(rawLocks.readLock(null).tryLock());
    assertEquals(Status.RESTORED, participant.handle(command).status);
    assertEquals(1, store.target("target").journal.getGeneration());
    File[] backups = temporary.getRoot().listFiles(file -> file.getName().startsWith("database_backup_"));
    assertEquals("Duplicate RESTORE must not back up or DROP again", 1, backups.length);
    sourceIntent.commit(1, "operation", 1);
    when(cluster.activate(database, localState)).thenAnswer(invocation -> {
      assertTrue(participant.isApplying("target"));
      assertFalse(store.isEligible("target")); assertFalse(rawLocks.readLock(null).tryLock());
      assertEquals(H2RestoreJournal.State.READY, store.target("target").journal.getState());
      return true;
    });
    H2RecoveryCommand apply = H2RecoveryCommand.apply("target", SHA, sourceIntent.snapshot());
    assertEquals(Status.DECIDED, participant.handle(apply).status);
    assertTrue(participant.isEligible("target")); assertFalse(participant.isApplying("target"));
    assertEquals(Status.DECIDED, participant.handle(apply).status);
    verify(cluster, times(1)).activate(database, localState);
    assertTrue(rawLocks.readLock(null).tryLock()); rawLocks.readLock(null).unlock();
    try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement statement = connection.createStatement();
        java.sql.ResultSet rows = statement.executeQuery("SELECT ID FROM RECOVERED")) { assertTrue(rows.next()); assertEquals(11, rows.getInt(1)); }
  }

  @Test(timeout = 20000) public void failedImportStaysQuarantinedAndDuplicateCannotRunDropAgain() throws Exception {
    H2RecoveryCommand command = prepare(artifact("CREATE TABLE PARTIAL(ID INT); BAD SQL;"));
    assertEquals(Status.FAILED, participant.handle(command).status);
    assertEquals(H2RestoreJournal.State.FAILED, store.target("target").journal.getState());
    assertFalse(participant.isEligible("target")); assertTrue(Files.exists(command.validatedPath()));
    assertEquals(Status.FAILED, participant.handle(command).status);
    sourceIntent.decide(1, "operation", H2ActivationIntent.Decision.ABORT);
    assertEquals(Status.DECIDED, participant.handle(H2RecoveryCommand.apply("target", SHA, sourceIntent.snapshot())).status);
    assertFalse(participant.isEligible("target"));
    verify(cluster, never()).activate(any(), any());
    verify(cluster, times(2)).deactivate(database, localState); // PREPARE 先移除路由，ABORT 再幂等核对。
    assertTrue(rawLocks.readLock(null).tryLock()); rawLocks.readLock(null).unlock();
  }

  @Test(timeout = 25000) public void callerTimeoutAndLateSqlSuccessCannotOverrideTheDurableAbort() throws Exception {
    String alias = "CREATE ALIAS PAUSE_IMPORT FOR \"" + getClass().getName() + ".pauseImport\"; CALL PAUSE_IMPORT();";
    H2RecoveryCommand command = prepare(artifact("CREATE TABLE RECOVERED(ID INT); " + alias + " INSERT INTO RECOVERED VALUES(12);"));
    executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    java.util.concurrent.Future<Reply> first = executor.submit(() -> participant.handle(command));
    assertTrue(entered.await(10, TimeUnit.SECONDS));
    try { first.get(20, TimeUnit.MILLISECONDS); fail("Caller deadline should expire while SQL still runs"); }
    catch (java.util.concurrent.TimeoutException expected) { }
    assertEquals(Status.BUSY, participant.handle(command).status);
    sourceIntent.decide(1, "operation", H2ActivationIntent.Decision.ABORT);
    H2RecoveryCommand abort = H2RecoveryCommand.apply("target", SHA, sourceIntent.snapshot());
    assertEquals(Status.FAILED, participant.handle(abort).status);
    assertFalse(rawLocks.readLock(null).tryLock());
    proceed.countDown(); assertEquals(Status.FAILED, first.get(10, TimeUnit.SECONDS).status);
    assertEquals(1, imports.get()); assertFalse(participant.isEligible("target"));
    assertEquals(Status.DECIDED, participant.handle(abort).status);
    assertFalse(participant.isEligible("target")); verify(cluster, never()).activate(any(), any());
  }

  @Test(timeout = 25000) public void membershipChangeDuringSqlInvalidatesLateCompletion() throws Exception {
    H2RecoveryCommand command = prepare(artifact("CREATE TABLE RECOVERED(ID INT); CREATE ALIAS PAUSE_IMPORT FOR \""
        + getClass().getName() + ".pauseImport\"; CALL PAUSE_IMPORT();"));
    executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    java.util.concurrent.Future<Reply> first = executor.submit(() -> participant.handle(command));
    assertTrue(entered.await(10, TimeUnit.SECONDS));
    stateView = new MembershipSnapshot(source, 2, Arrays.asList(source, local));
    proceed.countDown(); assertEquals(Status.FAILED, first.get(10, TimeUnit.SECONDS).status);
    assertEquals(H2RestoreJournal.State.FAILED, store.target("target").journal.getState());
    assertEquals(Status.FAILED, participant.handle(command).status);
    assertFalse(rawLocks.readLock(null).tryLock()); assertEquals(1, imports.get());
  }

  @Test(timeout = 20000) public void restartKeepsPendingFenceAndRejectsTheOldRestoreSession() throws Exception {
    H2RecoveryCommand command = prepare(artifact("CREATE TABLE RECOVERED(ID INT);"));
    assertEquals(Status.RESTORED, participant.handle(command).status);
    store.close();
    Map<String, String> identities = new LinkedHashMap<>(); identities.put("source", SHA); identities.put("target", SHA);
    store = new H2RecoveryStore(directory, "cluster", "target", identities);
    createParticipant();
    assertFalse(rawLocks.readLock(null).tryLock()); assertFalse(participant.isEligible("target"));
    assertEquals(Status.FAILED, participant.handle(command).status);
    assertEquals(H2RestoreJournal.State.RESTORED, store.target("target").journal.getState());
    verify(cluster, never()).activate(any(), any());
  }

  @Test(timeout = 20000) public void mismatchedDigestAndUnsafeArchiveFailBeforeDatabaseDrop() throws Exception {
    Path artifact = artifact("CREATE TABLE RECOVERED(ID INT);"); H2RecoveryCommand command = prepare(artifact);
    Files.write(artifact, new byte[] {1, 2, 3});
    assertEquals(Status.FAILED, participant.handle(command).status);
    try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement statement = connection.createStatement();
        java.sql.ResultSet rows = statement.executeQuery("SELECT ID FROM OLD_DATA")) { assertTrue(rows.next()); assertEquals(9, rows.getInt(1)); }
    File[] backups = temporary.getRoot().listFiles(file -> file.getName().startsWith("database_backup_"));
    assertEquals(0, backups.length); assertFalse(participant.isEligible("target"));
  }

  @Test public void oneChannelViewChangeRejectsPrepareBeforeAnyFenceOrSql() throws Exception {
    Operation unknownOwner = new Operation("target", SHA, "not-configured", 1, "operation", 0,
        source, sourceLock, stateView, lockView);
    assertEquals(Status.FAILED, participant.handle(H2RecoveryCommand.prepare(unknownOwner)).status);
    assertEquals(H2ActivationIntent.Decision.NONE, store.target("target").intent.snapshot().decision);
    Operation original = operation();
    lockView = new MembershipSnapshot(sourceLock, 2, Arrays.asList(sourceLock, localLock));
    assertEquals(Status.FAILED, participant.handle(H2RecoveryCommand.prepare(original)).status);
    assertEquals(H2ActivationIntent.Decision.NONE, store.target("target").intent.snapshot().decision);
    assertTrue(rawLocks.readLock(null).tryLock()); rawLocks.readLock(null).unlock();
    verify(cluster, never()).getDecoder();
  }

  @Test(timeout = 20000) public void unconfirmedCommitCannotActivateEvenWithAMatchingSerializedProof() throws Exception {
    H2RecoveryCommand command = prepare(artifact("CREATE TABLE RECOVERED(ID INT);"));
    assertEquals(Status.RESTORED, participant.handle(command).status);
    Path forged = temporary.getRoot().toPath().resolve("unconfirmed.intent");
    H2ActivationIntent.create(forged, SHA);
    try (H2ActivationIntent record = new H2ActivationIntent(forged, SHA)) {
      H2ActivationIntent.Snapshot pending = sourceIntent.snapshot();
      record.prepare(pending.epoch, pending.operation, pending.owner, pending.ownerDatabase,
          pending.membership, pending.digest, pending.participants, pending.databases);
      record.commit(1, "operation", 1);
      assertEquals(Status.FAILED, participant.handle(H2RecoveryCommand.apply("target", SHA, record.snapshot())).status);
    }
    assertEquals(H2ActivationIntent.Decision.PENDING, store.target("target").intent.snapshot().decision);
    assertFalse(participant.isEligible("target")); assertFalse(rawLocks.readLock(null).tryLock());
    verify(cluster, never()).activate(any(), any());
  }

  @Test(timeout = 20000) public void ambiguousStateToLockMemberMappingCannotConfirmCommit() throws Exception {
    H2RecoveryCommand command = prepare(artifact("CREATE TABLE RECOVERED(ID INT);"));
    assertEquals(Status.RESTORED, participant.handle(command).status); sourceIntent.commit(1, "operation", 1);
    doAnswer(invocation -> {
      Map<Member, Reply> result = responses(); Reply target = result.get(local);
      result.put(local, new Reply(target.status, target.target, target.targetIdentity, target.localDatabase,
          local, sourceLock, target.intent, target.generation, target.operationEpoch, false, stateView, lockView));
      return result;
    }).when(state).executeAll(any());
    assertEquals(Status.FAILED, participant.handle(H2RecoveryCommand.apply("target", SHA, sourceIntent.snapshot())).status);
    assertFalse(participant.isEligible("target")); assertFalse(rawLocks.readLock(null).tryLock());
    verify(cluster, never()).activate(any(), any());
  }

  @Test(timeout = 20000) public void archiveWithDifferentEntryCannotTouchDatabaseOrExternalPath() throws Exception {
    Path artifact = store.inputPath("target", "operation");
    try (java.util.zip.ZipOutputStream output = new java.util.zip.ZipOutputStream(Files.newOutputStream(artifact))) {
      output.putNextEntry(new java.util.zip.ZipEntry("../outside.dump"));
      output.write("DROP ALL OBJECTS".getBytes(StandardCharsets.UTF_8)); output.closeEntry();
    }
    H2RecoveryCommand command = prepare(artifact);
    assertEquals(Status.FAILED, participant.handle(command).status);
    assertFalse(Files.exists(directory.resolve("outside.dump")));
    assertFalse(Files.exists(java.nio.file.Paths.get(artifact.toString().substring(0, artifact.toString().length() - 4))));
    try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement statement = connection.createStatement();
        java.sql.ResultSet rows = statement.executeQuery("SELECT ID FROM OLD_DATA")) { assertTrue(rows.next()); assertEquals(9, rows.getInt(1)); }
    verify(database, never()).setSyncing(anyBoolean());
  }

  @Test(timeout = 20000) public void restartReappliesDurableCommitWithoutRunningRestoreAgain() throws Exception {
    H2RecoveryCommand command = prepare(artifact("CREATE TABLE RECOVERED(ID INT);"));
    assertEquals(Status.RESTORED, participant.handle(command).status); sourceIntent.commit(1, "operation", 1);
    assertEquals(Status.DECIDED, participant.handle(H2RecoveryCommand.apply("target", SHA, sourceIntent.snapshot())).status);
    store.close();
    Map<String, String> identities = new LinkedHashMap<>(); identities.put("source", SHA); identities.put("target", SHA);
    store = new H2RecoveryStore(directory, "cluster", "target", identities);
    assertFalse(store.isEligible("target"));
    createParticipant();
    assertTrue(participant.isEligible("target"));
    verify(cluster, times(2)).activate(database, localState);
    File[] backups = temporary.getRoot().listFiles(file -> file.getName().startsWith("database_backup_"));
    assertEquals(1, backups.length); assertEquals(1, store.target("target").journal.getGeneration());
    assertTrue(rawLocks.readLock(null).tryLock()); rawLocks.readLock(null).unlock();
  }

  @Test(timeout = 20000) public void decisionOwnerPersistsCommitOnlyForTheExactRestoreReceiptBeforeApplying() throws Exception {
    Path artifact = artifact("CREATE TABLE RECOVERED(ID INT);"); String digest = H2RecoveryParticipant.digest(artifact);
    Operation operation = new Operation("target", SHA, "target", 1, "operation", 0, local, localLock, stateView, lockView,
        new java.util.HashSet<>(Arrays.asList("source", "target")));
    assertEquals(Status.PREPARED, participant.handle(H2RecoveryCommand.prepare(operation)).status);
    assertEquals(Status.INPUT_BOUND, participant.handle(H2RecoveryCommand.bind(operation, digest)).status);
    Reply restored = participant.handle(H2RecoveryCommand.restore(operation, digest, artifact.toString()));
    assertEquals(Status.RESTORED, restored.status);
    Reply wrong = new Reply(restored.status, restored.target, restored.targetIdentity, restored.localDatabase,
        restored.localState, restored.localLock, restored.intent, 2, restored.operationEpoch, false, stateView, lockView);
    try { participant.decide(operation, wrong); fail("A different generation cannot commit"); }
    catch (java.io.IOException expected) { }
    assertEquals(H2ActivationIntent.Decision.PENDING, store.target("target").intent.snapshot().decision);
    H2ActivationIntent.Snapshot committed = participant.decide(operation, restored);
    assertEquals(H2ActivationIntent.Decision.COMMIT, committed.decision); assertFalse(committed.finished);
    assertFalse(participant.isEligible("target")); assertFalse(rawLocks.readLock(null).tryLock());
    verify(cluster, never()).activate(any(), any());
    assertEquals(H2ActivationIntent.Decision.COMMIT, participant.abort("target").decision);
    assertEquals(Status.DECIDED, participant.handle(H2RecoveryCommand.apply("target", SHA, committed)).status);
    assertTrue(participant.isEligible("target")); verify(cluster).activate(database, localState);
  }

  @Test(timeout = 25000) public void stopRetainsStorageOwnershipUntilTheActualSqlCallerExits() throws Exception {
    H2RecoveryCommand command = prepare(artifact("CREATE TABLE RECOVERED(ID INT); CREATE ALIAS PAUSE_IMPORT FOR \""
        + getClass().getName() + ".pauseImport\"; CALL PAUSE_IMPORT();"));
    executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    java.util.concurrent.Future<Reply> first = executor.submit(() -> participant.handle(command));
    assertTrue(entered.await(10, TimeUnit.SECONDS));
    try { participant.stop(); fail("Physical restore must retain store ownership"); }
    catch (java.io.IOException expected) { }
    assertFalse(participant.isEligible("target"));
    proceed.countDown(); assertEquals(Status.FAILED, first.get(10, TimeUnit.SECONDS).status);
    Map<String, String> identities = new LinkedHashMap<>(); identities.put("source", SHA); identities.put("target", SHA);
    try (H2RecoveryStore reopened = new H2RecoveryStore(directory, "cluster", "target", identities)) {
      assertEquals(H2RestoreJournal.State.FAILED, reopened.target("target").journal.getState());
      assertFalse(reopened.isEligible("target"));
    }
    verify(cluster, never()).activate(any(), any());
  }

  @Test(timeout = 20000) public void acceptedDurableCommitCanReplayAfterTheOriginalOwnerLeaves() throws Exception {
    H2RecoveryCommand command = prepare(artifact("CREATE TABLE RECOVERED(ID INT);"));
    assertEquals(Status.RESTORED, participant.handle(command).status);
    // 模拟已经接受并持久化、但尚未应用路由的决定；不是仅相信 RPC 携带的对象。
    store.target("target").intent.commit(1, "operation", 1);
    H2ActivationIntent.Snapshot committed = store.target("target").intent.snapshot();
    stateView = new MembershipSnapshot(local, 2, java.util.Collections.singleton(local));
    lockView = new MembershipSnapshot(localLock, 2, java.util.Collections.singleton(localLock));
    doAnswer(invocation -> java.util.Collections.singletonMap(local, participant.handle(H2RecoveryCommand.query("target", SHA))))
        .when(state).executeAll(any());
    assertEquals(Status.DECIDED, participant.handle(H2RecoveryCommand.apply("target", SHA, committed)).status);
    assertTrue(participant.isEligible("target")); assertTrue(rawLocks.readLock(null).tryLock()); rawLocks.readLock(null).unlock();
    verify(cluster).activate(database, localState);
  }

  @Test public void newlyDiscoveredPendingRecordInstallsFenceWithoutOpeningAnOldSqlSession() throws Exception {
    Operation operation = operation();
    Path file = temporary.getRoot().toPath().resolve("peer.intent"); H2ActivationIntent.create(file, SHA);
    try (H2ActivationIntent peer = new H2ActivationIntent(file, SHA)) {
      peer.prepare(1, "operation", operation.owner(), "source", operation.membership(), SHA, operation.participants(), operation.databases);
      participant.installDiscovered("target", peer.snapshot());
    }
    assertFalse(rawLocks.readLock(null).tryLock()); assertFalse(participant.isEligible("target"));
    assertEquals(0, store.target("target").journal.getGeneration());
    assertEquals(Status.FAILED, participant.handle(H2RecoveryCommand.restore(operation, SHA, store.inputPath("target", "operation").toString())).status);
    verify(database, never()).setSyncing(anyBoolean()); verify(cluster, never()).activate(any(), any());
  }

  /** 真实 H2 服务端调用，用门闩控制迟到 SQL，不用 sleep 猜测清库阶段。 */
  public static int pauseImport() throws InterruptedException {
    imports.incrementAndGet(); entered.countDown();
    if (!proceed.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate was not released");
    return 1;
  }
}
