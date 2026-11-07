package net.sf.hajdbc.dialect.h2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/**
 * H2 每目标恢复记录。记录落盘后才能清库，实例独占文件锁直到停止。
 * 调用方须提供稳定持久目录及来自同步写锁的会话；本类不授予激活资格。
 * 原子替换之外的掉电持久性仍须按部署文件系统验收。
 */
public final class H2RestoreJournal implements AutoCloseable {
  public enum State { FAILED, APPLYING, RESTORED, READY }
  private final Path file;
  private final String identity;
  private final FileChannel channel;
  private final FileLock fileLock;
  private long generation;
  private long operationEpoch;
  private String operation = "";
  private String digest = "";
  private State state = State.FAILED;
  private String stage = "NONE";
  private String pinnedBackup = "";
  private String session;
  private long sessionSequence;
  private Ticket running;
  private boolean closed;
  private boolean broken;

  /** 创建待修复目标记录；已有记录绝不覆盖，缺失记录不自动认定健康。 */
  public static void createIsolated(Path file, String identity) throws IOException {
    requireText(identity);
    Properties p = properties(identity, 0, 0, "", "", State.FAILED, "NONE", "");
    try (FileChannel out = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      write(out, p);
    }
    H2RecoveryFiles.forceDirectory(file.toAbsolutePath().getParent());
  }

  /** 打开已有记录并排斥另一个 JVM/实例；损坏、缺失或身份不符均失败关闭。 */
  public H2RestoreJournal(Path file, String identity) throws IOException {
    requireText(identity);
    this.file = file.toAbsolutePath().normalize();
    this.identity = identity;
    channel = FileChannel.open(this.file.resolveSibling(this.file.getFileName() + ".lock"),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    FileLock acquired = null;
    try {
      acquired = channel.tryLock();
      if (acquired == null) throw new IOException("H2 restore journal is already owned");
      load();
      // JVM 重启使所有内存会话失效；磁盘上的执行中/导入完成都不是上线凭据。
      if (state == State.APPLYING) persist(generation, operationEpoch, operation, digest, State.FAILED);
      else if (state == State.READY) persist(generation, operationEpoch, operation, digest, State.RESTORED);
    } catch (IOException | RuntimeException | Error e) {
      if (acquired != null) try { acquired.release(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
      try { channel.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
      if (e instanceof OverlappingFileLockException) throw new IOException("H2 restore journal is already owned", e);
      throw e;
    }
    fileLock = acquired;
  }

  /** 由恢复专用锁处理器安装会话；不能覆盖执行中或尚未关闭的会话。 */
  public synchronized void openSession(long sequence, String value) throws IOException {
    check();
    requireText(value);
    if (sequence <= sessionSequence || sequence <= operationEpoch) throw new IOException("Restore session sequence is stale");
    if (session != null || running != null) throw new IOException("Previous restore session is not closed");
    sessionSequence = sequence;
    session = value;
  }

  /**
   * 接受新操作并先可靠写入 APPLYING。相同操作返回 null，表示只允许查询原状态。
   * Ticket 只能由本次接受返回，不能通过外部 generation 数字构造。
   */
  public synchronized Ticket begin(long sequence, String sessionId, long expected, String id, String sha256) throws IOException {
    checkSession(sequence, sessionId);
    requireText(id);
    if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new IOException("Invalid restore digest");
    if (operation.equals(id)) {
      if (sequence != operationEpoch || expected == Long.MAX_VALUE || generation != expected + 1 || !digest.equals(sha256))
        throw new IOException("Restore identity conflicts with accepted operation");
      return null;
    }
    if (running != null || expected != generation || generation == Long.MAX_VALUE)
      throw new IOException("Restore is running or generation is stale");
    // 在落盘前分配凭据；内存分配失败不能留下无执行者的已接受任务。
    Ticket ticket = new Ticket(sequence, sessionId, generation + 1, id, sha256);
    persist(ticket.generation, ticket.sequence, id, sha256, State.APPLYING, "ACCEPTED");
    running = ticket;
    return ticket;
  }

  /** 物理执行者真正退出后调用。关闭会话后的迟到成功只能写 FAILED。 */
  public synchronized void finish(Ticket ticket, boolean success) throws IOException {
    if (ticket == null || running != ticket) throw new IOException("Unknown restore execution");
    try {
      check();
      boolean current = ticket.sequence == sessionSequence && ticket.session.equals(session) && ticket.generation == generation
          && ticket.operation.equals(operation) && ticket.digest.equals(digest);
      persist(generation, operationEpoch, operation, digest, success && current ? State.RESTORED : State.FAILED);
    } finally {
      running = null;
    }
  }

  /** 对应 SQL 执行前持久记录阶段；会话已关闭或记录失败时阻止继续执行。 */
  public synchronized void progress(Ticket ticket, String nextStage) throws IOException {
    if (ticket == null || running != ticket) throw new IOException("Unknown restore execution");
    checkSession(ticket.sequence, ticket.session);
    if (state != State.APPLYING || nextStage == null || !nextStage.matches("[A-Z_]{1,32}"))
      throw new IOException("Invalid restore stage");
    persist(generation, operationEpoch, operation, digest, State.APPLYING, nextStage);
  }

  /** 先关闭接收入口，再持久隔离；不会假装物理任务已退出。 */
  public synchronized void invalidateSession(long sequence, String sessionId) throws IOException {
    check();
    if (sequence != sessionSequence || sessionId == null || !sessionId.equals(session)) return;
    session = null;
    // 已完成导入的事实保留，但无会话时不能凭它激活；只有持久 COMMIT 可确认上线。
    if (state != State.RESTORED && state != State.READY) persist(generation, operationEpoch, operation, digest, State.FAILED);
  }

  public synchronized long getGeneration() { return generation; }
  public synchronized long getOperationEpoch() { return operationEpoch; }
  public synchronized State getState() { return state; }
  public synchronized boolean isRunning() { return running != null; }
  public synchronized String getStage() { return stage; }
  public synchronized String getPinnedBackup() { return pinnedBackup; }

  /** 首次清库前的备份跨失败重试保留；只有明确提交 READY 后才解除额外保护。 */
  public synchronized void pinBackup(Ticket ticket, String path) throws IOException {
    if (ticket == null || running != ticket) throw new IOException("Unknown restore execution");
    checkSession(ticket.sequence, ticket.session);
    if (state != State.APPLYING || !"BACKUP".equals(stage) || path == null || path.isEmpty()
        || path.length() > 8192 || path.indexOf('\0') >= 0) throw new IOException("Invalid H2 backup protection");
    if (pinnedBackup.isEmpty()) persist(generation, operationEpoch, operation, digest, state, stage,
        java.nio.file.Paths.get(path).toAbsolutePath().normalize().toString());
  }

  /** 仅在激活意向已不可逆提交且恢复成功身份逐项匹配时，确认目标可上线。 */
  public synchronized void confirmCommitted(H2ActivationIntent intent) throws IOException {
    check();
    H2ActivationIntent.Snapshot committed = intent.snapshot();
    if (!identity.equals(intent.getIdentity()) || committed.decision != H2ActivationIntent.Decision.COMMIT
        || committed.epoch != operationEpoch || committed.restoredGeneration != generation || !operation.equals(committed.operation)
        || !digest.equals(committed.digest) || running != null
        || (state != State.RESTORED && state != State.READY))
      throw new IOException("H2 activation does not match completed restore");
    if (state != State.READY || !pinnedBackup.isEmpty())
      persist(generation, operationEpoch, operation, digest, State.READY, stage, "");
  }

  public synchronized boolean isReady() { return !closed && !broken && running == null && state == State.READY; }

  /** 仅供激活处理器核对导入凭据；返回 true 本身不授予服务或选主资格。 */
  public synchronized boolean isRestored(long sequence, String sessionId, long value, String id, String sha256) {
    return !closed && !broken && sequence == sessionSequence && sequence == operationEpoch
        && sessionId != null && sessionId.equals(session) && running == null
        && state == State.RESTORED && value == generation && operation.equals(id) && digest.equals(sha256);
  }

  private void checkSession(long sequence, String value) throws IOException {
    check();
    if (sequence != sessionSequence || value == null || !value.equals(session)) throw new IOException("Restore session is no longer valid");
  }

  private void check() throws IOException {
    if (closed || broken) throw new IOException("H2 restore journal is unavailable");
  }

  private void load() throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 65536)
      throw new IOException("Invalid H2 restore journal");
    Properties p = new Properties();
    try {
      p.load(new ByteArrayInputStream(Files.readAllBytes(file)));
      if (!"4".equals(p.getProperty("version")) || !identity.equals(p.getProperty("identity")))
        throw new IOException("H2 restore journal identity mismatch");
      generation = Long.parseLong(p.getProperty("generation"));
      operationEpoch = Long.parseLong(p.getProperty("operationEpoch"));
      operation = p.getProperty("operation");
      digest = p.getProperty("digest");
      state = State.valueOf(p.getProperty("state"));
      stage = p.getProperty("stage");
      pinnedBackup = p.getProperty("pinnedBackup");
      if (pinnedBackup == null || pinnedBackup.length() > 8192 || pinnedBackup.indexOf('\0') >= 0
          || (generation == 0 && !pinnedBackup.isEmpty()) || (state == State.READY && !pinnedBackup.isEmpty()))
        throw new IOException("Invalid pinned H2 backup");
      if (stage == null || !stage.matches("[A-Z_]{1,32}")) throw new IOException("Invalid persisted restore stage");
      if (generation < 0 || operationEpoch < 0 || operation == null || digest == null
          || (generation == 0 && (operationEpoch != 0 || !operation.isEmpty() || !digest.isEmpty() || state != State.FAILED))
          || (generation > 0 && (operationEpoch == 0 || operation.isEmpty() || !digest.matches("[0-9a-f]{64}"))))
        throw new IOException("Invalid H2 restore journal fields");
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IOException("Malformed H2 restore journal", e);
    }
  }

  private void persist(long value, long epoch, String id, String sha256, State next) throws IOException {
    persist(value, epoch, id, sha256, next, stage);
  }

  private void persist(long value, long epoch, String id, String sha256, State next, String nextStage) throws IOException {
    persist(value, epoch, id, sha256, next, nextStage, pinnedBackup);
  }
  private void persist(long value, long epoch, String id, String sha256, State next, String nextStage, String nextBackup) throws IOException {
    Path temporary = null;
    try {
      temporary = Files.createTempFile(file.getParent(), "h2-restore-", ".pending");
      try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        write(out, properties(identity, value, epoch, id, sha256, next, nextStage, nextBackup));
      }
      Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      H2RecoveryFiles.forceDirectory(file.getParent());
      generation = value;
      operationEpoch = epoch;
      operation = id;
      digest = sha256;
      state = next;
      stage = nextStage;
      pinnedBackup = nextBackup;
    } catch (IOException | RuntimeException | Error failure) {
      broken = true;
      session = null;
      state = State.FAILED;
      if (temporary != null) {
        try { Files.deleteIfExists(temporary); }
        catch (IOException | RuntimeException cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      }
      throw failure;
    }
  }

  private static Properties properties(String identity, long generation, long epoch, String operation, String digest, State state, String stage, String pinnedBackup) {
    Properties p = new Properties();
    p.setProperty("version", "4");
    p.setProperty("identity", identity);
    p.setProperty("generation", Long.toString(generation));
    p.setProperty("operationEpoch", Long.toString(epoch));
    p.setProperty("operation", operation);
    p.setProperty("digest", digest);
    p.setProperty("state", state.name());
    p.setProperty("stage", stage);
    p.setProperty("pinnedBackup", pinnedBackup);
    return p;
  }

  private static void write(FileChannel out, Properties p) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    p.store(bytes, "H2 restore state");
    ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
    while (buffer.hasRemaining()) out.write(buffer);
    out.force(true);
  }

  private static void requireText(String value) {
    if (value == null || value.isEmpty() || value.length() > 256 || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid restore identity");
  }

  /** 执行仍在运行时拒绝关闭文件锁，防止另一实例并发清库。 */
  @Override public synchronized void close() throws IOException {
    if (closed) return;
    if (running != null) throw new IOException("Restore execution has not exited");
    closed = true;
    session = null;
    try { fileLock.release(); } finally { channel.close(); }
  }

  /** 一次已持久接受的物理执行凭据；只在当前实例有效。 */
  public static final class Ticket {
    private final long sequence;
    private final String session;
    private final long generation;
    private final String operation;
    private final String digest;
    private Ticket(long sequence, String session, long generation, String operation, String digest) {
      this.sequence = sequence; this.session = session; this.generation = generation; this.operation = operation; this.digest = digest;
    }
  }
}
