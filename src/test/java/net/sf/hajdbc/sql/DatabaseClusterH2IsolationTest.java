package net.sf.hajdbc.sql;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import net.sf.hajdbc.DatabaseClusterConfiguration;
import net.sf.hajdbc.MockDatabase;
import net.sf.hajdbc.balancer.Balancer;
import net.sf.hajdbc.balancer.simple.SimpleBalancerFactory;
import net.sf.hajdbc.cache.DatabaseMetaDataCache;
import net.sf.hajdbc.cache.DatabaseMetaDataCacheFactory;
import net.sf.hajdbc.dialect.h2.H2RecoveryCoordinator;
import net.sf.hajdbc.dialect.h2.H2RecoveryParticipant;
import net.sf.hajdbc.dialect.h2.H2RecoveryStore;
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
import org.junit.Rule;
import org.junit.After;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 直接执行真实 DatabaseClusterImpl 激活/旧活动列表/启动回放入口，不用模型替代门禁。 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class DatabaseClusterH2IsolationTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @After public void clearStartupProperties() {
    System.clearProperty(DatabaseClusterImpl.STARTUP_ACTIVE_DATABASE_TIMEOUT_PROPERTY);
    System.clearProperty(DatabaseClusterImpl.STARTUP_RETRY_INTERVAL_PROPERTY);
  }

  @Test(timeout = 10000) public void startupWaitsForDiscoveryAndAnActualRoute() throws Exception {
    startupWaitsForDiscovery(false);
  }

  @Test(timeout = 10000) public void staleActiveIdsCannotReturnBeforeDiscoveryBuildsAnActualRoute() throws Exception {
    startupWaitsForDiscovery(true);
  }

  private void startupWaitsForDiscovery(boolean staleActiveIds) throws Exception {
    System.setProperty(DatabaseClusterImpl.STARTUP_ACTIVE_DATABASE_TIMEOUT_PROPERTY, "8000");
    System.setProperty(DatabaseClusterImpl.STARTUP_RETRY_INTERVAL_PROPERTY, "1");
    try (StartupFixture fixture = new StartupFixture(true, staleActiveIds);
        StartupCall call = new StartupCall(fixture.cluster)) {
      assertTrue("Startup must reach the route wait", fixture.waiting.await(5, TimeUnit.SECONDS));
      assertFalse(call.task.isDone());
      assertFalse(fixture.source.isActive());
      verify(fixture.cacheFactory, never()).createCache(any());
      assertFalse("Unknown eligibility must not activate", fixture.cluster.activate(fixture.source, fixture.local));
      fixture.coordinator.discover();
      assertTrue(fixture.cluster.activate(fixture.source, fixture.local));
      call.task.get(5, TimeUnit.SECONDS);
      assertTrue(fixture.balancer.contains(fixture.source));
      verify(fixture.cacheFactory).createCache(fixture.cluster);
    }
  }

  @Test(timeout = 10000) public void confirmedIsolationWithStaleActiveIdsTimesOutInsteadOfReturningEmptyRoute() throws Exception {
    System.setProperty(DatabaseClusterImpl.STARTUP_ACTIVE_DATABASE_TIMEOUT_PROPERTY, "15");
    System.setProperty(DatabaseClusterImpl.STARTUP_RETRY_INTERVAL_PROPERTY, "1");
    try (StartupFixture fixture = new StartupFixture(false, true)) {
      fixture.coordinator.discover();
      fixture.balancer.add(fixture.source); fixture.source.setActive(true);
      try {
        invokeStartupReplay(fixture.cluster);
        fail("An isolated route must not be handed to JDBC initialization");
      } catch (IllegalStateException expected) {
        assertTrue(expected.getMessage().contains("Timed out waiting for active database"));
      }
      assertTrue(fixture.balancer.isEmpty());
      assertFalse(fixture.source.isActive());
      verify(fixture.local, never()).activated(any());
      verify(fixture.cacheFactory, never()).createCache(any());
    }
  }

  @Test(timeout = 10000) public void startupRouteWaitCanBeInterruptedWithoutAdmittingTheDatabase() throws Exception {
    System.setProperty(DatabaseClusterImpl.STARTUP_ACTIVE_DATABASE_TIMEOUT_PROPERTY, "8000");
    System.setProperty(DatabaseClusterImpl.STARTUP_RETRY_INTERVAL_PROPERTY, "1");
    try (StartupFixture fixture = new StartupFixture(true, false);
        StartupCall call = new StartupCall(fixture.cluster)) {
      assertTrue(fixture.waiting.await(5, TimeUnit.SECONDS));
      call.thread.interrupt();
      try { call.task.get(5, TimeUnit.SECONDS); fail("Expected interruption"); }
      catch (java.util.concurrent.ExecutionException expected) {
        assertTrue(expected.getCause() instanceof InterruptedException);
      }
      assertTrue(fixture.balancer.isEmpty());
      assertFalse(fixture.source.isActive());
      verify(fixture.cacheFactory, never()).createCache(any());
    }
  }

  @Test(timeout = 10000) public void isolationRetainsTheRemoteHostButNeverRetainsLocalMasterEligibility() throws Exception {
    try (StartupFixture fixture = new StartupFixture(false, false)) {
      fixture.coordinator.discover();
      net.sf.hajdbc.state.health.ClusterHealthImpl health = new net.sf.hajdbc.state.health.ClusterHealthImpl(fixture.state);
      try {
        java.lang.reflect.Method round = health.getClass().getDeclaredMethod("doTask");
        round.setAccessible(true);
        Member remote = new AddressMember(org.jgroups.util.UUID.randomUUID());
        Member local = fixture.state.getLocal();
        set(health, "host", remote);
        health.setState(net.sf.hajdbc.state.distributed.NodeState.backup);
        round.invoke(health);
        assertSame(remote, health.getHost());
        assertEquals(net.sf.hajdbc.state.distributed.NodeState.ready, health.getState());
        assertFalse(health.isHost());
        assertFalse(fixture.cluster.activate(fixture.source, fixture.local));
        health.removed(remote);
        assertNull("Leaving peer must still clear the host", health.getHost());
        set(health, "host", local);
        health.setState(net.sf.hajdbc.state.distributed.NodeState.host);
        round.invoke(health);
        assertNull("Isolation must revoke local master identity", health.getHost());
        assertFalse(health.isHost());
      } finally { health.stop(); }
    }
  }

  @Test(timeout = 10000) public void identifiedLocalDatabaseRemainsReadableWhileStartupOwnsItsMonitor() throws Exception {
    try (StartupFixture fixture = new StartupFixture(true, false)) {
      set(fixture.cluster, "localDbId", "source");
      fixture.source.setLocal(true);
      CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
      Thread owner = new Thread(() -> {
        synchronized (fixture.cluster) {
          held.countDown();
          try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
      }, "test-h2-startup-monitor");
      FutureTask<MockDatabase> read = new FutureTask<>(fixture.cluster::getLocalDatabase);
      Thread reader = new Thread(read, "test-h2-local-database-read");
      try {
        owner.start(); assertTrue(held.await(5, TimeUnit.SECONDS)); reader.start();
        assertSame("Recovery must read the identified local database without waiting for start", fixture.source, read.get(1, TimeUnit.SECONDS));
      } finally {
        release.countDown(); owner.interrupt(); reader.interrupt(); owner.join(3000); reader.join(3000);
        assertFalse(owner.isAlive()); assertFalse(reader.isAlive());
      }
    }
  }

  @Test(timeout = 10000) public void startupRetryCanIdentifyAnAlreadyLocalActiveDatabaseWithoutReconfiguringIt() throws Exception {
    try (StartupFixture fixture = new StartupFixture(true, false)) {
      fixture.source.setIp(net.sf.hajdbc.util.LocalHost.getAllIp().iterator().next());
      fixture.source.setLocal(true);
      fixture.source.clean();
      fixture.source.setActive(true);
      assertSame(fixture.source, fixture.cluster.getLocalDatabase());
      assertTrue(fixture.source.isActive());
      assertTrue(fixture.source.isLocal());
      assertFalse(fixture.source.isDirty());
      assertTrue("Identifying config must not grant a route", fixture.balancer.isEmpty());
      verify(fixture.local, never()).activated(any());
    }
  }

  @Test(timeout = 10000) public void identifyingAnActiveNonLocalConfigCannotSilentlyRewriteItsLocalFlag() throws Exception {
    try (StartupFixture fixture = new StartupFixture(true, false)) {
      fixture.source.setIp(net.sf.hajdbc.util.LocalHost.getAllIp().iterator().next());
      fixture.source.setActive(true);
      try { fixture.cluster.getLocalDatabase(); fail("Active config must not be rewritten"); }
      catch (IllegalStateException expected) { }
      assertTrue(fixture.source.isActive());
      assertFalse(fixture.source.isLocal());
      assertTrue(fixture.balancer.isEmpty());
    }
  }

  /** 只把首次 JDBC 元数据初始化放在真实恢复入口之后，避免用模型掩盖空路由交接。 */
  private static void invokeStartupReplay(DatabaseClusterImpl cluster) throws Exception {
    java.lang.reflect.Method replay = DatabaseClusterImpl.class.getDeclaredMethod("recoverDatabase", boolean.class);
    replay.setAccessible(true);
    try { replay.invoke(cluster, true); }
    catch (java.lang.reflect.InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      throw (Error) cause;
    }
  }

  private static final class StartupCall implements AutoCloseable {
    final FutureTask<Void> task;
    final Thread thread;
    StartupCall(DatabaseClusterImpl cluster) {
      task = new FutureTask<>(() -> { invokeStartupReplay(cluster); return null; });
      thread = new Thread(task, "test-h2-startup-route");
      thread.start();
    }
    @Override public void close() throws Exception {
      task.cancel(true); thread.join(5000);
      assertFalse("No startup test thread may remain", thread.isAlive());
    }
  }

  private final class StartupFixture implements AutoCloseable {
    final MockDatabase source = new MockDatabase("source");
    final DatabaseClusterImpl<Void, MockDatabase> cluster;
    final Balancer balancer;
    final StateManager local = mock(StateManager.class);
    final DistributedStateManager state;
    final DatabaseMetaDataCacheFactory cacheFactory = mock(DatabaseMetaDataCacheFactory.class);
    final CountDownLatch waiting = new CountDownLatch(1);
    final H2RecoveryStore store;
    final H2RecoveryCoordinator coordinator;

    StartupFixture(boolean baselineActive, boolean staleActiveIds) throws Exception {
      DatabaseClusterConfiguration config = mock(DatabaseClusterConfiguration.class);
      java.util.concurrent.ConcurrentMap<String, MockDatabase> databases = new ConcurrentHashMap<>();
      databases.put("source", source); when(config.getDatabaseMap()).thenReturn(databases);
      when(config.getDatabaseMetaDataCacheFactory()).thenReturn(cacheFactory);
      when(cacheFactory.createCache(any())).thenReturn(mock(DatabaseMetaDataCache.class));
      cluster = new DatabaseClusterImpl<>("cluster", config, null);
      balancer = spy(new SimpleBalancerFactory().createBalancer(new java.util.TreeSet<>()));
      doAnswer(invocation -> {
        boolean empty = (Boolean) invocation.callRealMethod();
        if (empty) waiting.countDown();
        return empty;
      }).when(balancer).isEmpty();
      set(cluster, "balancer", balancer);
      state = mock(DistributedStateManager.class);
      when(state.getDatabaseCluster()).thenReturn(cluster); when(state.getLocalStateManager()).thenReturn(local);
      when(state.getActiveDatabases()).thenReturn(staleActiveIds ? java.util.Collections.singleton("source") : java.util.Collections.emptySet());
      when(state.recover()).thenReturn(java.util.Collections.emptyMap());
      set(cluster, "stateManager", state);
      Member member = new AddressMember(org.jgroups.util.UUID.randomUUID());
      when(state.getLocal()).thenReturn(member);
      Member lockMember = new AddressMember(org.jgroups.util.UUID.randomUUID());
      CommandDispatcher dispatcher = mock(CommandDispatcher.class), lockDispatcher = mock(CommandDispatcher.class);
      when(state.getDispatcher()).thenReturn(dispatcher); when(dispatcher.getLocal()).thenReturn(member);
      when(dispatcher.membershipSnapshot()).thenReturn(new MembershipSnapshot(member, 1, java.util.Collections.singleton(member)));
      when(lockDispatcher.getLocal()).thenReturn(lockMember);
      when(lockDispatcher.membershipSnapshot()).thenReturn(new MembershipSnapshot(lockMember, 1, java.util.Collections.singleton(lockMember)));
      CommandDispatcherFactory factory = mock(CommandDispatcherFactory.class);
      when(factory.createCommandDispatcher(anyString(), any(), any(), any())).thenReturn(lockDispatcher);
      DistributedLockManager locks = new DistributedLockManager(cluster, factory);
      set(cluster, "lockManager", locks);
      String hash = String.join("", java.util.Collections.nCopies(64, "a"));
      Map<String, String> identities = java.util.Collections.singletonMap("source", hash);
      Path directory = temporary.newFolder().toPath().resolve("recovery");
      H2RecoveryStore.initialize(directory, "cluster", "source", identities,
          baselineActive ? java.util.Collections.singleton("source") : java.util.Collections.emptySet());
      store = new H2RecoveryStore(directory, "cluster", "source", identities);
      H2RecoveryParticipant participant = new H2RecoveryParticipant(store, state, locks, "source");
      coordinator = new H2RecoveryCoordinator(store, participant, state, locks);
      when(state.getExtContext(H2RecoveryCoordinator.class.getName())).thenReturn(coordinator);
      when(state.executeAll(any())).thenAnswer(invocation -> java.util.Collections.singletonMap(member,
          participant.handle((H2RecoveryCommand) invocation.getArguments()[0])));
    }

    @Override public void close() throws Exception { coordinator.stop(); store.close(); }
  }

  @Test public void rawActivationActiveListAndStartupReplayCannotReinsertAnIsolatedDatabase() throws Exception {
    MockDatabase source = new MockDatabase("source"), target = new MockDatabase("target");
    DatabaseClusterConfiguration config = mock(DatabaseClusterConfiguration.class);
    java.util.concurrent.ConcurrentMap<String, MockDatabase> databases = new ConcurrentHashMap<>(); databases.put("source", source); databases.put("target", target);
    when(config.getDatabaseMap()).thenReturn(databases);
    DatabaseMetaDataCache cache = mock(DatabaseMetaDataCache.class);
    DatabaseMetaDataCacheFactory cacheFactory = mock(DatabaseMetaDataCacheFactory.class);
    when(config.getDatabaseMetaDataCacheFactory()).thenReturn(cacheFactory); when(cacheFactory.createCache(any())).thenReturn(cache);
    DatabaseClusterImpl<Void, MockDatabase> cluster = new DatabaseClusterImpl<>("cluster", config, null);
    Balancer balancer = new SimpleBalancerFactory().createBalancer(new java.util.TreeSet<>());
    set(cluster, "balancer", balancer);
    DistributedStateManager state = mock(DistributedStateManager.class); StateManager local = mock(StateManager.class);
    when(state.getDatabaseCluster()).thenReturn(cluster); when(state.getLocalStateManager()).thenReturn(local);
    when(state.getActiveDatabases()).thenReturn(new java.util.HashSet<>(databases.keySet()));
    set(cluster, "stateManager", state);
    SemaphoreLockManager raw = new SemaphoreLockManager(false); set(cluster, "lockManager", raw);
    Member member = new AddressMember(org.jgroups.util.UUID.randomUUID()), lockMember = new AddressMember(org.jgroups.util.UUID.randomUUID());
    MembershipSnapshot stateView = new MembershipSnapshot(member, 1, java.util.Collections.singleton(member));
    MembershipSnapshot lockView = new MembershipSnapshot(lockMember, 1, java.util.Collections.singleton(lockMember));
    CommandDispatcher dispatcher = mock(CommandDispatcher.class), lockDispatcher = mock(CommandDispatcher.class);
    when(state.getDispatcher()).thenReturn(dispatcher); when(dispatcher.getLocal()).thenReturn(member);
    when(dispatcher.membershipSnapshot()).thenReturn(stateView); when(lockDispatcher.getLocal()).thenReturn(lockMember);
    when(lockDispatcher.membershipSnapshot()).thenReturn(lockView);
    CommandDispatcherFactory factory = mock(CommandDispatcherFactory.class);
    when(factory.createCommandDispatcher(anyString(), any(), any(), any())).thenReturn(lockDispatcher);
    DistributedLockManager locks = new DistributedLockManager(cluster, factory); set(cluster, "lockManager", locks);
    String hash = String.join("", java.util.Collections.nCopies(64, "a"));
    Map<String, String> identities = new LinkedHashMap<>(); identities.put("source", hash); identities.put("target", hash);
    Path directory = temporary.getRoot().toPath().resolve("recovery");
    H2RecoveryStore.initialize(directory, "cluster", "source", identities, java.util.Collections.singleton("source"));
    try (H2RecoveryStore store = new H2RecoveryStore(directory, "cluster", "source", identities)) {
      H2RecoveryParticipant participant = new H2RecoveryParticipant(store, state, locks, "source");
      H2RecoveryCoordinator coordinator = new H2RecoveryCoordinator(store, participant, state, locks);
      when(state.getExtContext(H2RecoveryCoordinator.class.getName())).thenReturn(coordinator);
      when(state.executeAll(any())).thenAnswer(invocation -> java.util.Collections.singletonMap(member,
          participant.handle((H2RecoveryCommand) invocation.getArguments()[0])));
      coordinator.discover();
      balancer.add(target); target.setActive(true); // 模拟旧缓存/旧广播中已经存在的错误活动项。
      assertFalse(cluster.activate(target, local)); assertFalse(balancer.contains(target)); assertFalse(target.isActive());
      verify(local, never()).activated(any());
      assertTrue(cluster.activate(source, local)); assertTrue(source.isActive());
      balancer.add(target); target.setActive(true);
      cluster.checkActiveDatabases(new java.util.HashSet<>(Arrays.asList("source", "target")));
      assertTrue(balancer.contains(source)); assertFalse(balancer.contains(target)); assertFalse(target.isActive());
      balancer.add(target); target.setActive(true);
      java.lang.reflect.Method replay = DatabaseClusterImpl.class.getDeclaredMethod("recoverDatabase", boolean.class);
      replay.setAccessible(true); replay.invoke(cluster, false);
      assertTrue(balancer.contains(source)); assertFalse(balancer.contains(target)); assertFalse(target.isActive());
    }
  }

  private static void set(Object owner, String fieldName, Object value) throws Exception {
    java.lang.reflect.Field field = owner.getClass().getDeclaredField(fieldName); field.setAccessible(true); field.set(owner, value);
  }
}
