package net.sf.hajdbc.sql;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 直接执行真实 DatabaseClusterImpl 激活/旧活动列表/启动回放入口，不用模型替代门禁。 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class DatabaseClusterH2IsolationTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

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
    java.lang.reflect.Field field = DatabaseClusterImpl.class.getDeclaredField(fieldName); field.setAccessible(true); field.set(owner, value);
  }
}
