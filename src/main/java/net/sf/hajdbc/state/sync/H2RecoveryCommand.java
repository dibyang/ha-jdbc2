package net.sf.hajdbc.state.sync;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import net.sf.hajdbc.dialect.h2.H2ActivationIntent;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.distributed.MembershipSnapshot;
import net.sf.hajdbc.state.distributed.DistributedManager;
import net.sf.hajdbc.state.distributed.StateCommandContext;

/**
 * H2 恢复专用 RPC。与旧清库命令分离，探测不执行 SQL，无接收器时明确拒绝。
 * 恢复动作只允许经已安装的本机处理器执行，命令本身不重试或自动激活。
 */
public final class H2RecoveryCommand implements SyncCommand<H2RecoveryCommand.Reply> {
  private static final long serialVersionUID = 1L;
  public static final int PROTOCOL_VERSION = 1;
  public enum Action { PROBE, PREPARE, BIND_INPUT, RESTORE, QUERY, APPLY_DECISION }
  public enum Status { SUPPORTED, PREPARED, INPUT_BOUND, RESTORED, DECIDED, BUSY, FAILED, UNSUPPORTED }
  public final Action action;
  public final String target;
  public final String targetIdentity;
  public final Operation operation;
  public final String digest;
  public final String path;
  public final H2ActivationIntent.Snapshot evidence;

  private H2RecoveryCommand(Action action, String target, String identity, Operation operation,
      String digest, String path, H2ActivationIntent.Snapshot evidence) {
    this.action = action; this.target = target; this.targetIdentity = identity; this.operation = operation;
    this.digest = digest; this.path = path; this.evidence = evidence;
    validate();
  }

  public static H2RecoveryCommand probe(String target, String identity) {
    return new H2RecoveryCommand(Action.PROBE, target, identity, null, null, null, null);
  }
  public static H2RecoveryCommand query(String target, String identity) {
    return new H2RecoveryCommand(Action.QUERY, target, identity, null, null, null, null);
  }

  /** 决定应用中会再次查询各成员，查询不能排在正等待该查询的 APPLY 消息之后。 */
  @Override public boolean isOutOfBand() { return action == Action.PROBE || action == Action.QUERY; }
  public static H2RecoveryCommand prepare(Operation operation) {
    return new H2RecoveryCommand(Action.PREPARE, operation.target, operation.targetIdentity, operation, null, null, null);
  }
  public static H2RecoveryCommand bind(Operation operation, String digest) {
    return new H2RecoveryCommand(Action.BIND_INPUT, operation.target, operation.targetIdentity, operation, digest, null, null);
  }
  public static H2RecoveryCommand restore(Operation operation, String digest, String path) {
    return new H2RecoveryCommand(Action.RESTORE, operation.target, operation.targetIdentity, operation, digest, path, null);
  }
  public static H2RecoveryCommand apply(String target, String identity, H2ActivationIntent.Snapshot evidence) {
    return new H2RecoveryCommand(Action.APPLY_DECISION, target, identity, null, evidence.digest, null, evidence);
  }

  /** 接收时再次校验，不能依赖 Java 反序列化执行了构造函数。 */
  public void validate() {
    text(target); hash(targetIdentity);
    if (action == null) throw new IllegalArgumentException("Missing H2 recovery action");
    if (action == Action.PREPARE || action == Action.BIND_INPUT || action == Action.RESTORE) {
      if (operation == null) throw new IllegalArgumentException("Missing H2 recovery operation");
      operation.validate();
      if (!target.equals(operation.target) || !targetIdentity.equals(operation.targetIdentity))
        throw new IllegalArgumentException("H2 target identity mismatch");
    }
    if (action == Action.BIND_INPUT || action == Action.RESTORE) hash(digest);
    // 出站命令可能携带另一种 OS 的原生路径；allowed-roots 必须由接收端核对。
    if (action == Action.RESTORE && (path == null || path.isEmpty() || path.length() > 8192 || path.indexOf('\0') >= 0))
      throw new IllegalArgumentException("Invalid H2 restore path");
    if (action == Action.APPLY_DECISION) {
      if (evidence == null || evidence.epoch <= 0 || evidence.operation == null
          || (evidence.decision != H2ActivationIntent.Decision.COMMIT && evidence.decision != H2ActivationIntent.Decision.ABORT)
          || evidence.participants == null || evidence.participants.isEmpty()
          || evidence.databases == null || evidence.databases.size() != evidence.participants.size()
          || !evidence.databases.contains(evidence.ownerDatabase) || !evidence.databases.contains(target)
          || !evidence.participants.contains(evidence.owner)) throw new IllegalArgumentException("Missing durable decision evidence");
      text(evidence.operation); text(evidence.owner); text(evidence.ownerDatabase); text(evidence.membership);
      for (String participant : evidence.participants) text(participant);
      for (String database : evidence.databases) text(database);
      if (!java.util.Objects.equals(digest, evidence.digest)) throw new IllegalArgumentException("Conflicting decision input");
      if (evidence.decision == H2ActivationIntent.Decision.COMMIT) {
        hash(evidence.digest);
        if (evidence.restoredGeneration <= 0) throw new IllegalArgumentException("Missing restored generation");
      } else if (evidence.restoredGeneration != 0) throw new IllegalArgumentException("Invalid abort evidence");
    }
  }

  /** 沿用文件同步的路径边界，恢复命令不能绕过 allowed-roots 配置。 */
  public Path validatedPath() { return SyncFilePath.target(path); }

  @Override @SuppressWarnings("rawtypes")
  public Reply execute(StateCommandContext context) {
    validate();
    if (action == Action.RESTORE) validatedPath();
    DistributedManager manager = context instanceof DistributedManager ? (DistributedManager) context
        : context.getDatabaseCluster().getDistributedManager();
    Object handler = manager == null ? null : manager.getExtContext(Handler.class.getName());
    if (!(handler instanceof Handler)) return Reply.unsupported(target, targetIdentity);
    Reply reply = ((Handler) handler).handle(this);
    if (reply == null) throw new IllegalStateException("Missing H2 recovery response");
    return reply;
  }

  /** 本机处理器在依赖及持久保护初始化后发布；不通过命令序列化。 */
  public interface Handler { Reply handle(H2RecoveryCommand command); }

  /** 同一轮操作的不可变身份，包含两个通道各自的原始视图。 */
  public static final class Operation implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String target;
    public final String targetIdentity;
    public final String ownerDatabase;
    public final long epoch;
    public final String id;
    public final long expectedGeneration;
    public final Member ownerState;
    public final Member ownerLock;
    public final MembershipSnapshot stateView;
    public final MembershipSnapshot lockView;
    public final Set<String> databases;

    public Operation(String target, String identity, String ownerDatabase, long epoch, String id, long expectedGeneration,
        Member ownerState, Member ownerLock, MembershipSnapshot stateView, MembershipSnapshot lockView) {
      this(target, identity, ownerDatabase, epoch, id, expectedGeneration, ownerState, ownerLock, stateView, lockView,
          new java.util.HashSet<>(java.util.Arrays.asList(target, ownerDatabase)));
    }
    public Operation(String target, String identity, String ownerDatabase, long epoch, String id, long expectedGeneration,
        Member ownerState, Member ownerLock, MembershipSnapshot stateView, MembershipSnapshot lockView, Set<String> databases) {
      this.target = target; this.targetIdentity = identity; this.ownerDatabase = ownerDatabase;
      this.epoch = epoch; this.id = id; this.expectedGeneration = expectedGeneration;
      this.ownerState = ownerState; this.ownerLock = ownerLock; this.stateView = stateView; this.lockView = lockView;
      Set<String> stable = new TreeSet<>();
      if (databases != null) for (String database : databases) { text(database); stable.add(database); }
      this.databases = java.util.Collections.unmodifiableSet(stable);
      validate();
    }

    public void validate() {
      text(target); hash(targetIdentity); text(ownerDatabase); text(id);
      if (epoch <= 0 || expectedGeneration < 0 || expectedGeneration == Long.MAX_VALUE
          || stateView == null || lockView == null || ownerState == null || ownerLock == null
          || !stateView.getMembers().contains(ownerState) || !lockView.getMembers().contains(ownerLock)
          || databases == null || databases.size() != stateView.getMembers().size()
          || !databases.contains(ownerDatabase) || !databases.contains(target)
          || stateView.getMembers().size() != lockView.getMembers().size())
        throw new IllegalArgumentException("Invalid H2 recovery view or generation");
      for (String database : databases) text(database);
    }

    public String membership() { return stateView.getViewId() + "|" + lockView.getViewId(); }
    public String owner() { return MembershipSnapshot.memberId(ownerState); }
    public Set<String> participants() {
      Set<String> keys = new TreeSet<>();
      for (Member member : stateView.getMembers()) keys.add(MembershipSnapshot.memberId(member));
      return java.util.Collections.unmodifiableSet(keys);
    }
  }

  /** 恢复回包只含协议事实，不发送 SQL、密码、连接对象或任意异常对象。 */
  public static final class Reply implements Serializable {
    private static final long serialVersionUID = 1L;
    public final int protocol;
    public final Status status;
    public final String target;
    public final String targetIdentity;
    public final String localDatabase;
    public final Member localState;
    public final Member localLock;
    public final MembershipSnapshot stateView;
    public final MembershipSnapshot lockView;
    public final H2ActivationIntent.Snapshot intent;
    public final long generation;
    public final long operationEpoch;
    public final boolean running;
    public final String inputPath;
    public final boolean eligible;

    public Reply(Status status, String target, String identity, String localDatabase, Member localState, Member localLock,
        H2ActivationIntent.Snapshot intent, long generation, long operationEpoch, boolean running) {
      this(status, target, identity, localDatabase, localState, localLock, intent, generation, operationEpoch, running, null, null);
    }

    public Reply(Status status, String target, String identity, String localDatabase, Member localState, Member localLock,
        H2ActivationIntent.Snapshot intent, long generation, long operationEpoch, boolean running,
        MembershipSnapshot stateView, MembershipSnapshot lockView) {
      this(status, target, identity, localDatabase, localState, localLock, intent, generation, operationEpoch,
          running, stateView, lockView, null);
    }
    public Reply(Status status, String target, String identity, String localDatabase, Member localState, Member localLock,
        H2ActivationIntent.Snapshot intent, long generation, long operationEpoch, boolean running,
        MembershipSnapshot stateView, MembershipSnapshot lockView, String inputPath) {
      this(status, target, identity, localDatabase, localState, localLock, intent, generation, operationEpoch,
          running, stateView, lockView, inputPath, false);
    }
    public Reply(Status status, String target, String identity, String localDatabase, Member localState, Member localLock,
        H2ActivationIntent.Snapshot intent, long generation, long operationEpoch, boolean running,
        MembershipSnapshot stateView, MembershipSnapshot lockView, String inputPath, boolean eligible) {
      this.protocol = PROTOCOL_VERSION;
      this.status = status; this.target = target; this.targetIdentity = identity; this.localDatabase = localDatabase;
      this.localState = localState; this.localLock = localLock; this.intent = intent;
      this.generation = generation; this.operationEpoch = operationEpoch; this.running = running;
      this.stateView = stateView; this.lockView = lockView;
      this.inputPath = inputPath;
      this.eligible = eligible;
    }

    /** 确认回包来自目标成员且两个通道都已看到同一视图，不能只检查响应数量。 */
    public boolean matchesViews(Member responder, MembershipSnapshot expectedState, MembershipSnapshot expectedLock) {
      return protocol == PROTOCOL_VERSION && status != null && status != Status.UNSUPPORTED && status != Status.FAILED
          && responder != null && responder.equals(localState) && localDatabase != null && !localDatabase.isEmpty()
          && expectedState != null && expectedLock != null && expectedState.equals(stateView) && expectedLock.equals(lockView)
          && expectedState.getMembers().contains(localState) && expectedLock.getMembers().contains(localLock);
    }
    public static Reply unsupported(String target, String identity) {
      return new Reply(Status.UNSUPPORTED, target, identity, null, null, null, null, 0, 0, false);
    }
  }

  private static void text(String value) {
    if (value == null || value.isEmpty() || value.length() > 256 || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid H2 recovery identity");
  }
  private static void hash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid H2 recovery digest");
  }
}
