package net.sf.hajdbc.dialect.h2;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.distributed.MembershipSnapshot;
import net.sf.hajdbc.lock.distributed.DistributedLockManager;
import net.sf.hajdbc.lock.distributed.RestoreLockDescriptor;
import net.sf.hajdbc.state.distributed.DistributedStateManager;
import net.sf.hajdbc.state.sync.H2RecoveryCommand;
import net.sf.hajdbc.state.sync.H2RecoveryCommand.Operation;
import net.sf.hajdbc.state.sync.H2RecoveryCommand.Reply;
import net.sf.hajdbc.state.sync.H2RecoveryCommand.Status;

/**
 * 本机恢复命令接收器。准备/查询/完成在每目标的同一监视器内串行，SQL 在其外运行。
 * 不创建线程、不重试清库；网络超时只能使请求结果未知，不能使运行令牌消失。
 */
public final class H2RecoveryParticipant<Z, D extends Database<Z>> implements H2RecoveryCommand.Handler {
  private static final net.sf.hajdbc.logging.Logger LOG = net.sf.hajdbc.logging.LoggerFactory.getLogger(H2RecoveryParticipant.class);
  private final H2RecoveryStore store;
  private final DistributedStateManager<Z, D> state;
  private final DistributedLockManager locks;
  private final DatabaseCluster<Z, D> cluster;
  private final String localDatabase;
  private final Map<String, Session> sessions = new HashMap<>();
  private final ThreadLocal<String> applying = new ThreadLocal<>();
  private volatile boolean stopped;

  public H2RecoveryParticipant(H2RecoveryStore store, DistributedStateManager<Z, D> state,
      DistributedLockManager locks, String localDatabase) {
    if (!store.getLocalDatabase().equals(localDatabase)) throw new IllegalArgumentException("H2 recovery local identity mismatch");
    this.store = store; this.state = state; this.locks = locks; this.cluster = state.getDatabaseCluster();
    this.localDatabase = localDatabase;
    for (String database : store.databases()) sessions.put(database, new Session());
  }

  /** 必须在注册接收器、恢复活动库及开始健康调度之前完成。 */
  public void restoreWriteFences() throws Exception {
    for (String database : store.databases()) {
      H2RecoveryStore.Target target = store.target(database);
      synchronized (target) {
        H2ActivationIntent.Snapshot record = target.intent.snapshot();
        if (record.decision == H2ActivationIntent.Decision.NONE) continue;
        Session session = sessions.get(database);
        session.fence = locks.prepareRestoreLock(new RestoreLockDescriptor(locks.getLocalMember(), database,
            record.epoch, record.operation), target.intent, record.owner, record.ownerDatabase,
            record.membership, record.digest, record.participants, record.databases);
        // 已落盘的终局可以在启动时重建本地路由，PENDING 必须继续保持写保护。
        if (record.decision != H2ActivationIntent.Decision.PENDING) apply(database, target, session, record);
      }
    }
  }

  @Override public Reply handle(H2RecoveryCommand command) {
    command.validate();
    try {
      if (stopped) throw new IOException("H2 recovery participant is stopped");
      H2RecoveryStore.Target target = store.target(command.target);
      if (!target.identity.equals(command.targetIdentity)) throw new IOException("H2 target identity mismatch");
      if (command.action == H2RecoveryCommand.Action.RESTORE) return restore(command, target);
      if (command.action == H2RecoveryCommand.Action.APPLY_DECISION) verifyDecision(command);
      synchronized (target) {
        if (stopped) throw new IOException("H2 recovery participant is stopped");
        Session session = sessions.get(command.target);
        switch (command.action) {
          case PROBE: case QUERY: return reply(command, target, Status.SUPPORTED);
          case PREPARE:
            prepare(command.operation, target, session);
            return reply(command, target, Status.PREPARED);
          case BIND_INPUT:
            requireLive(command.operation, target, session);
            target.intent.bindInput(command.operation.epoch, command.operation.id, command.digest);
            return reply(command, target, Status.INPUT_BOUND);
          case APPLY_DECISION:
            installDiscovered(command.target, command.evidence);
            requireScope(target.intent.snapshot(), command.evidence);
            apply(command.target, target, session, command.evidence);
            return reply(command, target, Status.DECIDED);
          default: throw new IOException("Unsupported H2 recovery action");
        }
      }
    } catch (Exception failure) {
      // 不把 SQL、文件路径或可序列化异常对象放进回包；详细根因仍由本机执行日志保留。
      LOG.log(net.sf.hajdbc.logging.Level.WARN, failure, "H2 recovery {0} refused for target {1}", command.action, command.target);
      return new Reply(Status.FAILED, command.target, command.targetIdentity, localDatabase,
          state.getDispatcher().getLocal(), locks.getLocalMember(), null, 0, 0, false);
    } finally {
      if (stopped) {
        try { store.close(); }
        catch (IOException cleanup) {
          // 仍有物理任务时保留所有权；最后一个任务退出时会再次到达本 finally。
          LOG.log(net.sf.hajdbc.logging.Level.WARN, cleanup, "H2 recovery storage close deferred or failed");
        }
      }
    }
  }

  private void prepare(Operation operation, H2RecoveryStore.Target target, Session session) throws IOException {
    if (!store.databases().containsAll(operation.databases)) throw new IOException("Unknown H2 recovery decision participant");
    requireViews(operation);
    if (target.journal.isRunning()) throw new IOException("Previous H2 restore is physically running");
    store.pruneInputs(operation.target);
    session.fence = locks.prepareRestoreLock(new RestoreLockDescriptor(operation.ownerLock, operation.target,
        operation.epoch, operation.id), target.intent, operation.owner(), operation.ownerDatabase,
        operation.membership(), "", operation.participants(), operation.databases);
    if (session.operation == null || session.operation.epoch != operation.epoch || !session.operation.id.equals(operation.id)) {
      cluster.deactivate(cluster.getDatabase(operation.target), state.getLocalStateManager());
      if (localDatabase.equals(operation.target)) target.journal.openSession(operation.epoch, operation.id);
      session.operation = operation;
    }
  }

  private Reply restore(H2RecoveryCommand command, H2RecoveryStore.Target target) throws Exception {
    H2RestoreJournal.Ticket ticket;
    Session session = sessions.get(command.target);
    Operation operation = command.operation;
    synchronized (target) {
      if (stopped || !localDatabase.equals(command.target)) throw new IOException("Restore is not addressed to the local target");
      requireLive(operation, target, session);
      if (!command.digest.equals(target.intent.snapshot().digest)) throw new IOException("Unbound H2 restore input");
      if (!store.inputPath(command.target, operation.id).equals(command.validatedPath()))
        throw new IOException("H2 restore artifact is not owned by this operation");
      ticket = target.journal.begin(operation.epoch, operation.id, operation.expectedGeneration, operation.id, command.digest);
      if (ticket == null) return reply(command, target, target.journal.isRunning() ? Status.BUSY
          : restored(operation, command.digest, target) ? Status.RESTORED : Status.FAILED);
    }
    boolean successful = false;
    Throwable primary = null;
    try {
      synchronized (target) {
        requireLive(operation, target, session);
        target.journal.progress(ticket, DbRestore.Stage.INPUT.name());
      }
      Path input = command.validatedPath();
      if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) || !command.digest.equals(digest(input)))
        throw new IOException("H2 restore input digest mismatch");
      Path script = extractInput(input);
      DbRestore.Result result = new DbRestore(target.journal.getPinnedBackup()).restoreDetailed(cluster.getDatabase(command.target), cluster.getDecoder(),
          script.toFile(), true, new DbRestore.Progress() {
            public void before(DbRestore.Stage stage) throws IOException {
              synchronized (target) {
                requireLive(operation, target, session);
                target.journal.progress(ticket, stage.name());
              }
            }
            public void backupCreated(String path) throws IOException {
              synchronized (target) {
                requireLive(operation, target, session);
                H2RecoveryFiles.forceBackup(java.nio.file.Paths.get(path));
                target.journal.pinBackup(ticket, path);
              }
            }
          });
      synchronized (target) {
        requireLive(operation, target, session);
        successful = result.success;
      }
    } catch (Exception | Error failure) {
      primary = failure;
      throw failure;
    } finally {
      try {
        synchronized (target) { target.journal.finish(ticket, successful); }
      } catch (IOException | RuntimeException | Error cleanup) {
        if (primary == null) throw cleanup;
        if (cleanup != primary) primary.addSuppressed(cleanup);
      }
    }
    synchronized (target) {
      return reply(command, target, restored(operation, command.digest, target) ? Status.RESTORED : Status.FAILED);
    }
  }

  private boolean restored(Operation operation, String digest, H2RecoveryStore.Target target) {
    return target.journal.isRestored(operation.epoch, operation.id, operation.expectedGeneration + 1, operation.id, digest);
  }

  private void requireLive(Operation operation, H2RecoveryStore.Target target, Session session) throws IOException {
    if (stopped || session.operation == null || session.fence == null || !session.fence.isHeld())
      throw new IOException("H2 recovery session is unavailable");
    H2ActivationIntent.Snapshot record = target.intent.snapshot();
    if (record.decision != H2ActivationIntent.Decision.PENDING || record.epoch != operation.epoch
        || !record.operation.equals(operation.id) || !record.owner.equals(operation.owner())
        || !record.ownerDatabase.equals(operation.ownerDatabase) || !record.membership.equals(operation.membership())
        || !record.participants.equals(operation.participants()) || !record.databases.equals(operation.databases))
      throw new IOException("Stale H2 recovery session");
    try { requireViews(operation); }
    catch (IOException invalid) {
      if (localDatabase.equals(operation.target)) target.journal.invalidateSession(operation.epoch, operation.id);
      session.operation = null;
      throw invalid;
    }
  }

  private void requireViews(Operation operation) throws IOException {
    if (!operation.stateView.equals(state.getDispatcher().membershipSnapshot())
        || !operation.lockView.equals(locks.membershipSnapshot())) throw new IOException("H2 recovery membership changed");
  }

  /** 不直接信任携带的决定：在监视器外读取参与者已持久接受的同一不可逆决定。 */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private void verifyDecision(H2RecoveryCommand command) throws IOException {
    MembershipSnapshot view = state.getDispatcher().membershipSnapshot();
    MembershipSnapshot lockView = locks.membershipSnapshot();
    Map<Member, Reply> responses = (Map) state.executeAll(
        (net.sf.hajdbc.distributed.Command) H2RecoveryCommand.query(command.target, command.targetIdentity));
    if (responses == null || !responses.keySet().equals(view.getMembers())
        || !view.equals(state.getDispatcher().membershipSnapshot())) throw new IOException("Incomplete H2 decision query");
    int witnesses = 0;
    int completionWitnesses = 0;
    java.util.Set<Member> lockMembers = new java.util.HashSet<>();
    java.util.Set<String> databases = new java.util.HashSet<>();
    for (Map.Entry<Member, Reply> entry : responses.entrySet()) {
      Reply response = entry.getValue();
      if (response == null || !response.matchesViews(entry.getKey(), view, lockView)
          || !command.target.equals(response.target) || !command.targetIdentity.equals(response.targetIdentity)
          || !store.databases().contains(response.localDatabase)
          || !lockMembers.add(response.localLock) || !databases.add(response.localDatabase))
        throw new IOException("Invalid H2 decision responder");
      if (response.intent != null && response.intent.epoch == command.evidence.epoch
          && response.intent.operation.equals(command.evidence.operation)) {
        requireScope(response.intent, command.evidence, true);
        if (response.intent.decision == H2ActivationIntent.Decision.COMMIT
            || response.intent.decision == H2ActivationIntent.Decision.ABORT) {
          if (response.intent.decision != command.evidence.decision
              || response.intent.restoredGeneration != command.evidence.restoredGeneration)
            throw new IOException("Conflicting durable H2 recovery decisions");
          witnesses++;
          if (response.intent.allApplied) completionWitnesses++;
        }
      }
    }
    if (witnesses == 0 || (command.evidence.allApplied && completionWitnesses == 0) || !lockMembers.equals(lockView.getMembers())
        || !view.equals(state.getDispatcher().membershipSnapshot()) || !lockView.equals(locks.membershipSnapshot()))
      throw new IOException("H2 recovery decision evidence or membership is unavailable or ambiguous");
  }

  private void apply(String database, H2RecoveryStore.Target target, Session session,
      H2ActivationIntent.Snapshot proof) throws Exception {
    if (session.fence == null) throw new IOException("H2 activation has no write protection");
    if (target.journal.isRunning()) {
      if (proof.decision == H2ActivationIntent.Decision.ABORT) {
        // 立即关闭接收资格，使尚未开始的 DROP/COMMIT 在阶段回调处停止；锁仍等物理调用退出。
        target.intent.decide(proof.epoch, proof.operation, H2ActivationIntent.Decision.ABORT);
        if (session.operation != null && localDatabase.equals(database))
          target.journal.invalidateSession(proof.epoch, proof.operation);
        session.operation = null;
      }
      throw new IOException("H2 activation cannot run before physical completion");
    }
    session.fence.decideAndApply(proof.epoch, proof.operation, proof.decision, proof.restoredGeneration, () -> {
      applying.set(database);
      try {
        if (proof.decision == H2ActivationIntent.Decision.COMMIT) {
          if (localDatabase.equals(database)) {
            target.journal.confirmCommitted(target.intent);
            target.journal.invalidateSession(proof.epoch, proof.operation);
          }
          cluster.activate(cluster.getDatabase(database), state.getLocalStateManager());
        } else {
          if (session.operation != null && localDatabase.equals(database))
            target.journal.invalidateSession(proof.epoch, proof.operation);
          cluster.deactivate(cluster.getDatabase(database), state.getLocalStateManager());
        }
      } finally { applying.remove(); }
    });
    if (proof.allApplied) target.intent.allApplied(proof.epoch, proof.operation);
    session.operation = null;
  }

  public boolean isEligible(String database) { return !stopped && store.isEligible(database); }
  /** 仅决定落盘且写保护尚持有时，允许本机受控激活回调通过公共激活门禁。 */
  public boolean isApplying(String database) { return !stopped && database.equals(applying.get()); }

  /** 启动发现/新成员核对使用。只安装已从当前精确成员回包读出的记录，不开放旧 SQL 会话。 */
  public void installDiscovered(String database, H2ActivationIntent.Snapshot discovered) throws Exception {
    H2RecoveryStore.Target target = store.target(database);
    synchronized (target) {
      if (stopped) throw new IOException("H2 recovery participant is stopped");
      H2ActivationIntent.Snapshot local = target.intent.snapshot();
      if (discovered.epoch < local.epoch) return;
      if (discovered.epoch == local.epoch) {
        if (discovered.decision != H2ActivationIntent.Decision.NONE) {
          requireScope(local, discovered, true);
          if (local.digest.isEmpty() && !discovered.digest.isEmpty())
            target.intent.bindInput(local.epoch, local.operation, discovered.digest);
        }
        return;
      }
      if (!local.finished || target.journal.isRunning()) throw new IOException("Local H2 recovery is still unresolved");
      Session session = sessions.get(database);
      session.fence = locks.prepareRestoreLock(new RestoreLockDescriptor(locks.getLocalMember(), database,
          discovered.epoch, discovered.operation), target.intent, discovered.owner, discovered.ownerDatabase,
            discovered.membership, discovered.digest, discovered.participants, discovered.databases);
      session.operation = null;
    }
  }

  /** 发起节点先持久决定再发送 APPLY；没有匹配的目标完成回包不能形成 COMMIT。 */
  public H2ActivationIntent.Snapshot decide(Operation operation, Reply restored) throws IOException {
    H2RecoveryStore.Target target = store.target(operation.target);
    synchronized (target) {
      if (stopped || !localDatabase.equals(operation.ownerDatabase)) throw new IOException("Not the H2 recovery decision owner");
      requireLive(operation, target, sessions.get(operation.target));
      if (restored == null || restored.status != Status.RESTORED || !operation.target.equals(restored.localDatabase)
          || !operation.target.equals(restored.target) || !operation.targetIdentity.equals(restored.targetIdentity)
          || !restored.matchesViews(restored.localState, operation.stateView, operation.lockView)
          || restored.running || restored.operationEpoch != operation.epoch
          || restored.generation != operation.expectedGeneration + 1)
        throw new IOException("H2 restore completion is not confirmed");
      requireScope(target.intent.snapshot(), restored.intent);
      target.intent.commit(operation.epoch, operation.id, restored.generation);
      return target.intent.snapshot();
    }
  }

  /** 原发起节点重启也能对自己未决的操作撤销；已持久 COMMIT 绝不能反转。 */
  public H2ActivationIntent.Snapshot abort(String database) throws IOException {
    H2RecoveryStore.Target target = store.target(database);
    synchronized (target) {
      H2ActivationIntent.Snapshot record = target.intent.snapshot();
      if (stopped || !localDatabase.equals(record.ownerDatabase)) throw new IOException("Not the H2 recovery decision owner");
      if (record.decision == H2ActivationIntent.Decision.PENDING)
        target.intent.decide(record.epoch, record.operation, H2ActivationIntent.Decision.ABORT);
      return target.intent.snapshot();
    }
  }

  /** 仅原发起节点可根据完整回包主动形成全员完成标记，其余节点通过核验后的决定学习。 */
  public H2ActivationIntent.Snapshot allApplied(String database, long epoch, String operation) throws IOException {
    H2RecoveryStore.Target target = store.target(database);
    synchronized (target) {
      if (stopped || !localDatabase.equals(target.intent.snapshot().ownerDatabase)) throw new IOException("Not the H2 recovery completion owner");
      target.intent.allApplied(epoch, operation);
      return target.intent.snapshot();
    }
  }

  /** 只撤销当前进程会话，不反转持久 COMMIT；物理调用结束前文件锁仍由 store 持有。 */
  public void stop() throws IOException {
    stopped = true;
    for (String database : store.databases()) {
      H2RecoveryStore.Target target = store.target(database);
      synchronized (target) {
        Session session = sessions.get(database);
        if (session.operation != null && localDatabase.equals(database))
          target.journal.invalidateSession(session.operation.epoch, session.operation.id);
        session.operation = null;
      }
    }
    store.close();
  }

  private Reply reply(H2RecoveryCommand command, H2RecoveryStore.Target target, Status status) throws IOException {
    H2ActivationIntent.Snapshot intent = target.intent.snapshot();
    return new Reply(status, command.target, target.identity, localDatabase, state.getDispatcher().getLocal(),
        locks.getLocalMember(), intent, target.journal.getGeneration(),
        target.journal.getOperationEpoch(), target.journal.isRunning(),
        state.getDispatcher().membershipSnapshot(), locks.membershipSnapshot(),
        intent.epoch == 0 ? null : store.inputPath(command.target, intent.operation).toString(), store.isEligible(command.target));
  }

  private static void requireScope(H2ActivationIntent.Snapshot local, H2ActivationIntent.Snapshot proof) throws IOException {
    requireScope(local, proof, false);
  }
  private static void requireScope(H2ActivationIntent.Snapshot local, H2ActivationIntent.Snapshot proof,
      boolean allowUnbound) throws IOException {
    if (local == null || local.epoch != proof.epoch || !local.operation.equals(proof.operation)
        || !local.owner.equals(proof.owner) || !local.ownerDatabase.equals(proof.ownerDatabase)
        || !local.membership.equals(proof.membership) || (!local.digest.equals(proof.digest)
            && !(allowUnbound && local.decision == H2ActivationIntent.Decision.PENDING && local.digest.isEmpty()))
        || !local.participants.equals(proof.participants) || !local.databases.equals(proof.databases))
      throw new IOException("Conflicting H2 activation scope");
  }

  static String digest(Path input) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream stream = Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = new byte[8192];
      for (int count; (count = stream.read(bytes)) != -1;) digest.update(bytes, 0, count);
    }
    StringBuilder value = new StringBuilder(64);
    for (byte item : digest.digest()) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
    return value.toString();
  }

  /** 新协议只接收单脚本包，入口名称必须与本操作文件完全一致，不能展开任意 ZIP 路径。 */
  private static Path extractInput(Path artifact) throws IOException {
    String name = artifact.getFileName().toString();
    if (!name.endsWith(".dump.zip")) throw new IOException("Invalid H2 restore artifact");
    String scriptName = name.substring(0, name.length() - DbRestore.EXT_ZIP.length());
    Path script = artifact.resolveSibling(scriptName);
    boolean created = false;
    try (java.util.zip.ZipInputStream input = new java.util.zip.ZipInputStream(Files.newInputStream(artifact, LinkOption.NOFOLLOW_LINKS))) {
      java.util.zip.ZipEntry entry = input.getNextEntry();
      if (entry == null || entry.isDirectory() || !scriptName.equals(entry.getName())) throw new IOException("Conflicting H2 script entry");
      try (java.io.OutputStream output = Files.newOutputStream(script, java.nio.file.StandardOpenOption.CREATE_NEW,
          java.nio.file.StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
        created = true;
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
      }
      if (input.getNextEntry() != null) throw new IOException("Multiple H2 restore script entries");
      return script;
    } catch (IOException | RuntimeException | Error failure) {
      if (created) try { Files.deleteIfExists(script); }
      catch (IOException cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }
  private static final class Session { Operation operation; H2RestoreWriteFence fence; }
}
