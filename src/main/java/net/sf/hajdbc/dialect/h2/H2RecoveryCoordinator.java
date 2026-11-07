package net.sf.hajdbc.dialect.h2;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.distributed.MembershipSnapshot;
import net.sf.hajdbc.lock.distributed.DistributedLockManager;
import net.sf.hajdbc.state.distributed.DistributedStateManager;
import net.sf.hajdbc.state.sync.H2RecoveryCommand;
import net.sf.hajdbc.state.sync.H2RecoveryCommand.Operation;
import net.sf.hajdbc.state.sync.H2RecoveryCommand.Reply;
import net.sf.hajdbc.state.sync.H2RecoveryCommand.Status;

/** H2 同步发起端。恢复请求只发一次，未知结果只查询/重放持久决定，不重放 SQL。 */
public final class H2RecoveryCoordinator<Z, D extends Database<Z>> {
  private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
  private final H2RecoveryStore store;
  private final H2RecoveryParticipant<Z, D> participant;
  private final DistributedStateManager<Z, D> state;
  private final DistributedLockManager locks;
  private final DatabaseCluster<Z, D> cluster;
  private final ReentrantLock operationLock = new ReentrantLock();
  private volatile boolean discovered;
  private volatile boolean stopped;
  private final Map<String, Long> settled = new HashMap<>();
  private String observedView = "";
  private long nextCheck;

  public H2RecoveryCoordinator(H2RecoveryStore store, H2RecoveryParticipant<Z, D> participant,
      DistributedStateManager<Z, D> state, DistributedLockManager locks) {
    this.store = store; this.participant = participant; this.state = state; this.locks = locks;
    this.cluster = state.getDatabaseCluster();
  }

  public static Session current(DatabaseCluster<?, ?> cluster) {
    Session session = CURRENT.get();
    return session != null && session.owner.cluster == cluster ? session : null;
  }

  public static H2RecoveryCoordinator<?, ?> get(DatabaseCluster<?, ?> cluster) {
    if (cluster == null || cluster.getDistributedManager() == null) return null;
    Object value = cluster.getDistributedManager().getExtContext(H2RecoveryCoordinator.class.getName());
    return value instanceof H2RecoveryCoordinator ? (H2RecoveryCoordinator<?, ?>) value : null;
  }

  public static boolean installed(net.sf.hajdbc.state.distributed.StateCommandContext<?, ?> context) {
    if (context instanceof net.sf.hajdbc.state.distributed.DistributedManager
        && ((net.sf.hajdbc.state.distributed.DistributedManager<?, ?>) context).getExtContext(H2RecoveryCoordinator.class.getName()) != null) return true;
    return get(context.getDatabaseCluster()) != null;
  }

  /** 调用方不再另取普通全局写锁，也不得在本方法返回后绕开决定再次直接 activate。 */
  public void synchronize(String target, Work work) throws Exception {
    operationLock.lockInterruptibly();
    Operation operation = null;
    try {
      if (stopped || !discovered || CURRENT.get() != null) throw new IOException("H2 recovery coordinator is not ready");
      Snapshot previous = reconcileTarget(target);
      H2ActivationIntent.Snapshot latest = latest(previous);
      if (!latest.allApplied && !previous.databases.keySet().containsAll(latest.databases))
        throw new IOException("Original participants must acknowledge the previous H2 decision before a new restore");
      for (Reply reply : previous.responses.values()) {
        if (reply.running || !reply.intent.finished || reply.intent.epoch != latest.epoch
            || reply.intent.decision != latest.decision) throw new IOException("Previous H2 recovery is unresolved");
      }
      Reply targetReply = previous.databases.get(target);
      if (targetReply == null || !previous.databases.containsKey(store.getLocalDatabase())) throw new IOException("H2 source or target member is unavailable");
      long epoch = Math.max(latest.epoch, targetReply.operationEpoch);
      if (epoch == Long.MAX_VALUE) throw new IOException("H2 recovery epoch exhausted");
      operation = new Operation(target, store.target(target).identity, store.getLocalDatabase(), epoch + 1,
          UUID.randomUUID().toString(), targetReply.generation, state.getDispatcher().getLocal(),
          locks.getLocalMember(), previous.stateView, previous.lockView, previous.databases.keySet());
      // 本地先落盘，远端任一准备结果未知也留有可自动查询的发起端记录。
      Reply prepared = participant.handle(H2RecoveryCommand.prepare(operation));
      if (prepared.status != Status.PREPARED) throw new IOException("Local H2 recovery prepare failed");
      Snapshot preparedAll = broadcast(H2RecoveryCommand.prepare(operation), previous, Status.PREPARED);
      Session session = new Session(this, operation, preparedAll);
      CURRENT.set(session);
      work.run();
      if (session.restored == null) throw new IOException("H2 synchronization did not confirm a restore");
      H2ActivationIntent.Snapshot committed = participant.decide(operation, session.restored);
      broadcast(H2RecoveryCommand.apply(target, operation.targetIdentity, committed), preparedAll, Status.DECIDED);
      H2ActivationIntent.Snapshot completed = participant.allApplied(target, operation.epoch, operation.id);
      broadcast(H2RecoveryCommand.apply(target, operation.targetIdentity, completed), preparedAll, Status.DECIDED);
    } catch (Exception | Error failure) {
      if (operation != null) {
        try {
          H2ActivationIntent.Snapshot decision = participant.abort(target);
          // abort 保留已存在的 COMMIT。应用失败仍保留写保护，交给后续核对。
          if (decision.decision == H2ActivationIntent.Decision.ABORT || decision.decision == H2ActivationIntent.Decision.COMMIT) {
            Snapshot current = collect(target);
            broadcast(H2RecoveryCommand.apply(target, operation.targetIdentity, decision), current, Status.DECIDED);
          }
        } catch (Exception | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      }
      throw failure;
    } finally {
      CURRENT.remove();
      operationLock.unlock();
    }
  }

  /** 状态通道启动后、恢复活动库和健康选主前调用；发现未决先安装本地写保护。 */
  public void discover() throws Exception {
    operationLock.lockInterruptibly();
    try {
      if (stopped) throw new IOException("H2 recovery coordinator is stopped");
      for (String database : store.databases()) rememberSettled(database, reconcileTarget(database));
      observedView = viewKey();
      discovered = true;
    } finally { operationLock.unlock(); }
  }

  /** 复用已有调度线程；同步正在执行时不等待、不创建并行恢复任务。 */
  public void reconcile() throws Exception {
    if (stopped || !operationLock.tryLock()) return;
    try {
      String view = viewKey();
      long now = System.nanoTime();
      if (view.equals(observedView) && nextCheck != 0 && now - nextCheck < 0) return;
      nextCheck = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      if (!view.equals(observedView)) { settled.clear(); observedView = view; }
      for (String database : store.databases()) {
        H2ActivationIntent.Snapshot record = store.target(database).intent.snapshot();
        if (!Long.valueOf(record.epoch).equals(settled.get(database))) rememberSettled(database, reconcileTarget(database));
      }
      discovered = true;
    } finally { operationLock.unlock(); }
  }

  private String viewKey() {
    return state.getDispatcher().membershipSnapshot().getViewId() + "|" + locks.membershipSnapshot().getViewId();
  }

  private void rememberSettled(String database, Snapshot snapshot) throws IOException {
    H2ActivationIntent.Snapshot record = latest(snapshot);
    if (record.decision == H2ActivationIntent.Decision.PENDING) return;
    if (record.decision != H2ActivationIntent.Decision.NONE && !record.allApplied && !snapshot.databases.keySet().containsAll(record.databases)) return;
    for (Reply reply : snapshot.responses.values())
      if (reply.intent.epoch != record.epoch || reply.intent.decision != record.decision || !reply.intent.finished) return;
    settled.put(database, record.epoch);
  }

  public boolean isEligible(String database) { return discovered && !stopped && participant.isEligible(database); }
  public boolean isLocalEligible() { return isEligible(store.getLocalDatabase()); }
  public boolean isApplying(String database) { return !stopped && participant.isApplying(database); }
  public void stop() throws IOException { stopped = true; discovered = false; participant.stop(); }

  private Snapshot reconcileTarget(String database) throws Exception {
    Snapshot snapshot = collect(database);
    H2ActivationIntent.Snapshot record = latest(snapshot);
    if (record.decision == H2ActivationIntent.Decision.NONE) return snapshot;
    participant.installDiscovered(database, record);
    if (record.decision == H2ActivationIntent.Decision.PENDING && record.ownerDatabase.equals(store.getLocalDatabase())) {
      record = participant.abort(database);
      snapshot = collect(database);
    }
    if (record.decision == H2ActivationIntent.Decision.COMMIT || record.decision == H2ActivationIntent.Decision.ABORT) {
      snapshot = broadcast(H2RecoveryCommand.apply(database, store.target(database).identity, record), snapshot, Status.DECIDED);
      if (!record.allApplied && record.ownerDatabase.equals(store.getLocalDatabase())
          && snapshot.databases.keySet().containsAll(record.databases)) {
        record = participant.allApplied(database, record.epoch, record.operation);
        snapshot = broadcast(H2RecoveryCommand.apply(database, store.target(database).identity, record), snapshot, Status.DECIDED);
      }
    }
    return snapshot;
  }

  private Snapshot collect(String database) throws IOException {
    MembershipSnapshot stateView = state.getDispatcher().membershipSnapshot();
    MembershipSnapshot lockView = locks.membershipSnapshot();
    return validate(database, stateView, lockView, all(H2RecoveryCommand.probe(database, store.target(database).identity)), null);
  }

  private Snapshot broadcast(H2RecoveryCommand command, Snapshot expected, Status status) throws IOException {
    requireViews(expected.stateView, expected.lockView);
    Snapshot result = validate(command.target, expected.stateView, expected.lockView, all(command), status);
    for (Reply reply : result.responses.values()) {
      H2ActivationIntent.Snapshot record = reply.intent;
      if (command.operation != null) {
        Operation operation = command.operation;
        if (record.epoch != operation.epoch || !record.operation.equals(operation.id)
            || !record.owner.equals(operation.owner()) || !record.ownerDatabase.equals(operation.ownerDatabase)
            || !record.membership.equals(operation.membership()) || !record.participants.equals(operation.participants())
            || !record.databases.equals(operation.databases)
            || record.decision != H2ActivationIntent.Decision.PENDING
            || (command.digest != null && !command.digest.equals(record.digest)))
          throw new IOException("Stale H2 recovery acknowledgement");
      } else if (command.evidence != null) {
        H2ActivationIntent.Snapshot proof = command.evidence;
        if (record.epoch != proof.epoch || !record.operation.equals(proof.operation)
            || !record.owner.equals(proof.owner) || !record.ownerDatabase.equals(proof.ownerDatabase)
            || !record.membership.equals(proof.membership) || !record.participants.equals(proof.participants)
            || !record.databases.equals(proof.databases)
            || !record.digest.equals(proof.digest) || record.decision != proof.decision
            || record.restoredGeneration != proof.restoredGeneration || !record.finished || (proof.allApplied && !record.allApplied))
          throw new IOException("H2 decision was not applied by every member");
      }
    }
    requireViews(expected.stateView, expected.lockView);
    return result;
  }

  private Snapshot validate(String database, MembershipSnapshot stateView, MembershipSnapshot lockView,
      Map<Member, Reply> responses, Status status) throws IOException {
    if (responses == null || !responses.keySet().equals(stateView.getMembers())) throw new IOException("Incomplete H2 recovery replies");
    Map<String, Reply> databases = new HashMap<>(); Set<Member> lockMembers = new HashSet<>();
    for (Map.Entry<Member, Reply> entry : responses.entrySet()) {
      Reply reply = entry.getValue();
      if (reply == null || !reply.matchesViews(entry.getKey(), stateView, lockView)
          || !database.equals(reply.target) || !store.target(database).identity.equals(reply.targetIdentity)
          || reply.intent == null || (status != null && reply.status != status)
          || !store.databases().contains(reply.localDatabase) || databases.put(reply.localDatabase, reply) != null
          || !lockMembers.add(reply.localLock)) throw new IOException("Invalid H2 recovery member or result");
    }
    if (!lockMembers.equals(lockView.getMembers())) throw new IOException("H2 state and lock membership differ");
    requireViews(stateView, lockView);
    return new Snapshot(stateView, lockView, responses, databases);
  }

  private void requireViews(MembershipSnapshot stateView, MembershipSnapshot lockView) throws IOException {
    if (stopped || !stateView.equals(state.getDispatcher().membershipSnapshot()) || !lockView.equals(locks.membershipSnapshot()))
      throw new IOException("H2 recovery membership changed");
  }

  /** 同一代次可以有尚未收到绑定的空摘要，但不能存在两个相反的终局或不同操作。 */
  private static H2ActivationIntent.Snapshot latest(Snapshot snapshot) throws IOException {
    H2ActivationIntent.Snapshot latest = null;
    for (Reply reply : snapshot.responses.values()) {
      H2ActivationIntent.Snapshot value = reply.intent;
      if (latest == null || value.epoch > latest.epoch) latest = value;
    }
    for (Reply reply : snapshot.responses.values()) {
      H2ActivationIntent.Snapshot value = reply.intent;
      if (value.epoch != latest.epoch || value.epoch == 0) continue;
      if (!value.operation.equals(latest.operation) || !value.owner.equals(latest.owner)
          || !value.ownerDatabase.equals(latest.ownerDatabase) || !value.membership.equals(latest.membership)
          || !value.participants.equals(latest.participants)
          || !value.databases.equals(latest.databases)
          || (!value.digest.isEmpty() && !latest.digest.isEmpty() && !value.digest.equals(latest.digest)))
        throw new IOException("Conflicting H2 recovery records");
      boolean terminal = value.decision == H2ActivationIntent.Decision.COMMIT || value.decision == H2ActivationIntent.Decision.ABORT;
      boolean latestTerminal = latest.decision == H2ActivationIntent.Decision.COMMIT || latest.decision == H2ActivationIntent.Decision.ABORT;
      if (terminal && latestTerminal && (value.decision != latest.decision || value.restoredGeneration != latest.restoredGeneration))
        throw new IOException("Conflicting durable H2 recovery decisions");
      if ((terminal && !latestTerminal) || (terminal && latestTerminal && value.allApplied && !latest.allApplied)
          || (!latestTerminal && latest.digest.isEmpty() && !value.digest.isEmpty())) latest = value;
    }
    return latest;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private Map<Member, Reply> all(H2RecoveryCommand command) {
    return (Map) state.executeAll((net.sf.hajdbc.distributed.Command) command);
  }

  @FunctionalInterface public interface Work { void run() throws Exception; }
  private static final class Snapshot {
    final MembershipSnapshot stateView, lockView;
    final Map<Member, Reply> responses; final Map<String, Reply> databases;
    Snapshot(MembershipSnapshot stateView, MembershipSnapshot lockView, Map<Member, Reply> responses, Map<String, Reply> databases) {
      this.stateView = stateView; this.lockView = lockView; this.responses = responses; this.databases = databases;
    }
  }

  /** 仅同步调用线程可见；不把 JDBC 连接或可执行任务发送给远端。 */
  public static final class Session {
    private final H2RecoveryCoordinator<?, ?> owner;
    private final Operation operation;
    private Snapshot prepared;
    private Reply restored;
    private boolean sourceChecked;
    private Session(H2RecoveryCoordinator<?, ?> owner, Operation operation, Snapshot prepared) {
      this.owner = owner; this.operation = operation; this.prepared = prepared;
    }
    public File dumpFile() throws IOException {
      Path zip = owner.store.inputPath(operation.target, operation.id);
      return new File(zip.toString().substring(0, zip.toString().length() - 4));
    }
    public void checkSource(String database) throws IOException {
      if (operation.target.equals(database)) throw new IOException("H2 restore cannot use its own target as source");
      if (!owner.store.getLocalDatabase().equals(database)) throw new IOException("H2 export must use the initiating node's local database");
      Snapshot source = owner.collect(database);
      Reply local = source.databases.get(database);
      if (local == null || !local.eligible || local.running) throw new IOException("H2 source is not eligible");
      sourceChecked = true;
    }
    public void restore(Database<?> target, File script) throws Exception {
      if (!operation.target.equals(target.getId()) || !sourceChecked || restored != null || !dumpFile().equals(script))
        throw new IOException("H2 restore is outside the current synchronization operation");
      File zip = new File(script.getPath() + ".zip");
      String digest = H2RecoveryParticipant.digest(zip.toPath());
      if (owner.participant.handle(H2RecoveryCommand.bind(operation, digest)).status != Status.INPUT_BOUND)
        throw new IOException("Local H2 input binding failed");
      prepared = owner.broadcast(H2RecoveryCommand.bind(operation, digest), prepared, Status.INPUT_BOUND);
      Reply targetReply = prepared.databases.get(operation.target);
      if (targetReply.inputPath == null) throw new IOException("Missing receiver-owned H2 input path");
      if (!owner.state.getSyncMgr().upload(targetReply.localState, zip, targetReply.inputPath)) throw new IOException("H2 restore upload failed");
      Reply result = owner.state.getSyncMgr().executeOnce(targetReply.localState,
          H2RecoveryCommand.restore(operation, digest, targetReply.inputPath));
      if (result == null || result.status != Status.RESTORED
          || !targetReply.localState.equals(result.localState) || !targetReply.localLock.equals(result.localLock))
        throw new IOException("H2 restore failed or its one-shot result is unknown");
      restored = result;
    }
  }
}
