package net.sf.hajdbc.dialect.h2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 真实迁移标记、目录、恢复记录和信号量；状态通道启动/网络故障仍由后续集成测试验收。 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class H2RecoveryRuntimeTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final Member member = new AddressMember(org.jgroups.util.UUID.randomUUID());
  private final Member lockMember = new AddressMember(org.jgroups.util.UUID.randomUUID());
  private final Map<String, Object> ext = new ConcurrentHashMap<>();
  private final Map<String, String> saved = new LinkedHashMap<>();
  private DatabaseCluster cluster;
  private DistributedStateManager state;
  private Database source, target;
  private SemaphoreLockManager raw;
  private H2RecoveryRuntime runtime;
  private Path base;
  private MembershipSnapshot stateView, lockView;

  @Before public void setup() throws Exception {
    for (String key : Arrays.asList(H2RecoveryRuntime.DIRECTORY_PROPERTY, "ha-jdbc.sync.allowed-roots")) saved.put(key, System.getProperty(key));
    base = temporary.getRoot().toPath().resolve("persistent");
    System.setProperty(H2RecoveryRuntime.DIRECTORY_PROPERTY, base.toString());
    System.setProperty("ha-jdbc.sync.allowed-roots", temporary.getRoot().getPath());
    cluster = mock(DatabaseCluster.class); state = mock(DistributedStateManager.class);
    source = database("source"); target = database("target"); raw = new SemaphoreLockManager(false);
    when(cluster.getId()).thenReturn("cluster"); when(cluster.getLocalDatabase()).thenReturn(source);
    when(cluster.getDialect()).thenReturn(new H2Dialect()); when(cluster.getDistributedManager()).thenReturn(state);
    when(cluster.getDatabase("source")).thenReturn(source); when(cluster.getDatabase("target")).thenReturn(target);
    when(state.getDatabaseCluster()).thenReturn(cluster); when(state.getLocalStateManager()).thenReturn(mock(StateManager.class));
    when(state.getExtContext(anyString())).thenAnswer(invocation -> ext.get(invocation.getArguments()[0]));
    doAnswer(invocation -> { ext.put((String) invocation.getArguments()[0], invocation.getArguments()[1]); return null; })
        .when(state).setExtContext(anyString(), any());
    stateView = new MembershipSnapshot(member, 1, java.util.Collections.singleton(member));
    lockView = new MembershipSnapshot(lockMember, 1, java.util.Collections.singleton(lockMember));
    CommandDispatcher dispatcher = mock(CommandDispatcher.class), lockDispatcher = mock(CommandDispatcher.class);
    when(state.getDispatcher()).thenReturn(dispatcher); when(dispatcher.getLocal()).thenReturn(member);
    when(dispatcher.membershipSnapshot()).thenAnswer(invocation -> stateView);
    when(lockDispatcher.getLocal()).thenReturn(lockMember); when(lockDispatcher.membershipSnapshot()).thenAnswer(invocation -> lockView);
    when(cluster.getLockManager()).thenReturn(raw);
    CommandDispatcherFactory factory = mock(CommandDispatcherFactory.class);
    when(factory.createCommandDispatcher(anyString(), any(), any(), any())).thenReturn(lockDispatcher);
    DistributedLockManager locks = new DistributedLockManager(cluster, factory);
    when(cluster.getLockManager()).thenReturn(locks);
    when(state.executeAll(any())).thenAnswer(invocation -> {
      H2RecoveryCommand command = (H2RecoveryCommand) invocation.getArguments()[0];
      return java.util.Collections.singletonMap(member, ((H2RecoveryCommand.Handler) ext.get(H2RecoveryCommand.Handler.class.getName())).handle(command));
    });
  }

  private Database database(String id) {
    Database database = mock(Database.class); when(database.getId()).thenReturn(id);
    when(database.getLocation()).thenReturn("jdbc:h2:tcp://127.0.0.1:9092//manager/data/" + id);
    return database;
  }
  private void install() throws Exception { runtime = H2RecoveryRuntime.install(state, Arrays.asList(source, target)); }
  private Path storeDirectory() throws IOException {
    try (java.util.stream.Stream<Path> files = Files.list(base)) { return files.filter(file -> file.toString().endsWith(".store")).findFirst().get(); }
  }
  private Map<String, String> identities(Path directory) throws IOException {
    Properties p = new Properties(); try (java.io.InputStream file = Files.newInputStream(directory.resolve("catalog"))) { p.load(file); }
    Map<String, String> identities = new LinkedHashMap<>();
    for (String key : p.stringPropertyNames()) if (key.endsWith(".id"))
      identities.put(p.getProperty(key), p.getProperty(key.substring(0, key.length() - 3) + ".identity"));
    return identities;
  }

  @After public void cleanup() {
    if (runtime != null) runtime.stop();
    for (Map.Entry<String, String> property : saved.entrySet()) {
      if (property.getValue() == null) System.clearProperty(property.getKey()); else System.setProperty(property.getKey(), property.getValue());
    }
  }

  @Test public void installsBeforeAdmissionAndStoppedContextStillRejectsBothLegacyCommands() throws Exception {
    install(); H2RecoveryCoordinator coordinator = H2RecoveryCoordinator.get(cluster);
    assertNotNull(coordinator); assertFalse(coordinator.isLocalEligible());
    runtime.discover(); assertTrue(coordinator.isLocalEligible());
    assertTrue(Files.isRegularFile(storeDirectory().resolve("catalog")));
    runtime.stop(); assertFalse(coordinator.isLocalEligible());
    H2RunScriptCommand old = new H2RunScriptCommand(); old.setPath("must-not-open.dump");
    H2RunScriptCommand2 old2 = new H2RunScriptCommand2(); old2.setPath("must-not-open.dump");
    assertFalse(old.execute(state)); assertFalse(old2.execute(state));
    verify(cluster, never()).getDecoder();
    assertSame(coordinator, H2RecoveryCoordinator.get(cluster));
  }

  @Test public void missingCatalogIsNotRecreatedAndFailedOpenReleasesStorage() throws Exception {
    install(); runtime.stop(); Path directory = storeDirectory(); byte[] saved = Files.readAllBytes(directory.resolve("catalog"));
    Files.delete(directory.resolve("catalog"));
    reject(this::install); assertFalse(Files.exists(directory.resolve("catalog")));
    Files.write(directory.resolve("catalog"), saved); install(); runtime.discover();
    assertTrue(H2RecoveryCoordinator.get(cluster).isLocalEligible());
  }

  @Test public void missingWholeStoreCannotCreateAnotherHealthyMigrationBaseline() throws Exception {
    install(); runtime.stop(); Path directory = storeDirectory();
    Files.move(directory, temporary.getRoot().toPath().resolve("saved-store"));
    reject(this::install); assertFalse(Files.exists(directory));
    assertFalse(H2RecoveryCoordinator.get(cluster).isLocalEligible());
  }

  @Test public void restartRebuildsPendingFenceBeforeDiscoveryAndAutomaticallyAbortsTheOldOwnerSession() throws Exception {
    install(); runtime.stop(); Path directory = storeDirectory(); Map<String, String> identities = identities(directory);
    try (H2RecoveryStore store = new H2RecoveryStore(directory, "cluster", "source", identities)) {
      store.target("target").intent.prepare(1, "old-operation", MembershipSnapshot.memberId(member), "source",
          stateView.getViewId() + "|" + lockView.getViewId(), "",
          new java.util.HashSet<>(Arrays.asList(MembershipSnapshot.memberId(member), "old-target-incarnation")),
          new java.util.HashSet<>(Arrays.asList("source", "target")));
    }
    install(); assertFalse(raw.readLock(null).tryLock()); assertFalse(H2RecoveryCoordinator.get(cluster).isLocalEligible());
    runtime.discover(); assertTrue(H2RecoveryCoordinator.get(cluster).isLocalEligible());
    assertFalse(H2RecoveryCoordinator.get(cluster).isEligible("target"));
    assertTrue(raw.readLock(null).tryLock()); raw.readLock(null).unlock();
    runtime.stop();
    try (H2RecoveryStore store = new H2RecoveryStore(directory, "cluster", "source", identities)) {
      assertEquals(H2ActivationIntent.Decision.ABORT, store.target("target").intent.snapshot().decision);
      assertTrue(store.target("target").intent.snapshot().finished);
    }
  }

  @Test public void jdbcHostAliasAndPasswordChangeDoNotForgetTheSameDatabaseRecord() throws Exception {
    install(); runtime.stop();
    when(source.getLocation()).thenReturn("jdbc:h2:tcp://localhost:9191//manager/data/source;PASSWORD=not-stored");
    install(); runtime.discover(); assertTrue(H2RecoveryCoordinator.get(cluster).isLocalEligible());
    when(target.getLocation()).thenReturn("jdbc:h2:tcp://localhost:9191//manager/data/different");
    runtime.stop(); reject(this::install);
  }

  @Test public void relativeRecoveryRootsAndAmbiguousDefaultPathsAreRejected() throws Exception {
    System.setProperty(H2RecoveryRuntime.DIRECTORY_PROPERTY, "relative");
    reject(() -> H2RecoveryRuntime.baseDirectory("jdbc:h2:mem:db"));
    System.clearProperty(H2RecoveryRuntime.DIRECTORY_PROPERTY);
    reject(() -> H2RecoveryRuntime.baseDirectory("jdbc:h2:./manager"));
    assertEquals(java.nio.file.Paths.get(System.getProperty("user.home"), "manager.ha-jdbc-recovery"),
        H2RecoveryRuntime.baseDirectory("jdbc:h2:~/manager"));
    String absolute = temporary.getRoot().toPath().resolve("db").toString();
    assertEquals(java.nio.file.Paths.get(absolute + ".ha-jdbc-recovery"), H2RecoveryRuntime.baseDirectory("jdbc:h2:" + absolute));
    assertEquals("/manager/db", H2RecoveryRuntime.databaseName("jdbc:h2:ssl://host:9092//manager/db;USER=sa"));
    reject(() -> H2RecoveryRuntime.databaseName("jdbc:h2:file:/data/a\\;b"));
  }

  @Test public void inMemoryTargetsCannotClaimDurableRecoveryAcrossRestart() throws Exception {
    when(target.getLocation()).thenReturn("jdbc:h2:mem:ephemeral");
    reject(this::install); assertFalse(Files.exists(base));
  }

  @FunctionalInterface private interface Attempt { void run() throws Exception; }
  private static void reject(Attempt attempt) throws Exception {
    try { attempt.run(); fail("Unsafe runtime initialization succeeded"); } catch (IOException expected) { }
  }
}
