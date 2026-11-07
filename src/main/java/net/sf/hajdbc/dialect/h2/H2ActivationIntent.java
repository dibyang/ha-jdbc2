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
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * 每参与成员、每目标的恢复激活意向。提交/撤销决定一旦持久化便不可反转。
 * 本类不执行网络、路由或解锁；调用方必须按“决定落盘、应用路由、释放写保护”顺序处理。
 * 使用已有持久目录；缺失或损坏不默认健康，首次迁移需显式建立空记录。
 */
public final class H2ActivationIntent implements AutoCloseable {
  public enum Decision { NONE, PENDING, COMMIT, ABORT }
  private final Path file;
  private final String identity;
  private final FileChannel channel;
  private final FileLock ownership;
  private Snapshot current;
  private boolean unavailable;
  private boolean closed;

  /** 仅首次迁移/新目标初始化使用；绝不覆盖既有文件。 */
  public static void create(Path file, String identity) throws IOException {
    text(identity);
    Snapshot empty = new Snapshot(0, "", "", "", "", "", 0, Collections.emptySet(), Decision.NONE, true);
    try (FileChannel out = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      write(out, encode(identity, empty));
    }
    H2RecoveryFiles.forceDirectory(file.toAbsolutePath().getParent());
  }

  /** 独占打开，重启时原样恢复未决决定，不将 PENDING 自动转为 ABORT。 */
  public H2ActivationIntent(Path file, String identity) throws IOException {
    text(identity);
    this.file = file.toAbsolutePath().normalize();
    this.identity = identity;
    channel = FileChannel.open(this.file.resolveSibling(this.file.getFileName() + ".lock"),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    FileLock lock = null;
    try {
      lock = channel.tryLock();
      if (lock == null) throw new IOException("Recovery activation intent is already owned");
      current = load();
    } catch (IOException | RuntimeException | Error failure) {
      if (lock != null) try { lock.release(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
      try { channel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
      if (failure instanceof OverlappingFileLockException)
        throw new IOException("Recovery activation intent is already owned", failure);
      throw failure;
    }
    ownership = lock;
  }

  /**
   * 在确认准备完成前持久记录完整参与集合。未收口期间禁止更换操作。
   * 同一操作重复准备幂等，但代次、源身份和参与集合必须逐项一致。
   */
  public synchronized void prepare(long epoch, String operation, String owner, String digest,
      Set<String> participants) throws IOException {
    prepare(epoch, operation, owner, owner, "local", digest, participants);
  }

  /** 持久记录发起节点稳定身份和双通道视图摘要，供原进程消失后的自动接管。 */
  public synchronized void prepare(long epoch, String operation, String owner, String ownerDatabase,
      String membership, String digest, Set<String> participants) throws IOException {
    prepare(epoch, operation, owner, ownerDatabase, membership, digest, participants, Collections.emptySet());
  }

  public synchronized void prepare(long epoch, String operation, String owner, String ownerDatabase,
      String membership, String digest, Set<String> participants, Set<String> databases) throws IOException {
    check();
    text(operation); text(owner); text(ownerDatabase); text(membership);
    if (digest == null || (!digest.isEmpty() && !digest.matches("[0-9a-f]{64}"))) throw new IOException("Invalid input digest");
    if (participants == null || participants.isEmpty() || participants.size() > 64)
      throw new IOException("Invalid recovery participant set");
    TreeSet<String> members = new TreeSet<>();
    for (String member : participants) { text(member); members.add(member); }
    if (!members.contains(owner)) throw new IOException("Owner is not a recovery participant");
    if (databases == null || databases.size() > 64 || (!databases.isEmpty()
        && (databases.size() != members.size() || !databases.contains(ownerDatabase))))
      throw new IOException("Invalid stable recovery participants");
    for (String database : databases) text(database);
    if (epoch == current.epoch && operation.equals(current.operation)) {
      if (!owner.equals(current.owner) || !ownerDatabase.equals(current.ownerDatabase)
          || !membership.equals(current.membership) || (!digest.isEmpty() && !digest.equals(current.digest))
          || !members.equals(current.participants) || !databases.equals(current.databases))
        throw new IOException("Conflicting activation identity");
      return;
    }
    if (!current.finished || epoch <= current.epoch) throw new IOException("Recovery activation is unresolved or stale");
    persist(new Snapshot(epoch, operation, owner, ownerDatabase, membership, digest, 0, members, databases, Decision.PENDING, false));
  }

  /** 持锁导出后只绑定一次真实输入摘要；准备阶段不需要提前猜测导出文件内容。 */
  public synchronized void bindInput(long epoch, String operation, String digest) throws IOException {
    checkIdentity(epoch, operation);
    if (digest == null || !digest.matches("[0-9a-f]{64}")) throw new IOException("Invalid input digest");
    if (current.digest.equals(digest)) return;
    if (current.decision != Decision.PENDING || !current.digest.isEmpty()) throw new IOException("Restore input cannot be replaced");
    persist(new Snapshot(current.epoch, current.operation, current.owner, current.ownerDatabase, current.membership,
        digest, 0, current.participants, current.databases, Decision.PENDING, false));
  }

  /** 决定须来自协议核验后的协调者或持久决定证据；本方法不代替来源/视图校验。 */
  public synchronized void decide(long epoch, String operation, Decision decision) throws IOException {
    checkIdentity(epoch, operation);
    if (decision != Decision.ABORT) throw new IOException("Commit requires an explicit restored generation");
    if (current.decision == decision) return;
    if (current.decision != Decision.PENDING) throw new IOException("Recovery decision cannot be reversed");
    persist(current.with(decision, false));
  }

  /** 只有匹配的明确恢复成功代次才能形成不可逆提交决定，不能拿准备代次替代。 */
  public synchronized void commit(long epoch, String operation, long restoredGeneration) throws IOException {
    checkIdentity(epoch, operation);
    if (restoredGeneration <= 0 || !current.digest.matches("[0-9a-f]{64}"))
      throw new IOException("Restore completion is not confirmed");
    if (current.decision == Decision.COMMIT) {
      if (current.restoredGeneration != restoredGeneration) throw new IOException("Conflicting restored generation");
      return;
    }
    if (current.decision != Decision.PENDING) throw new IOException("Recovery decision cannot be reversed");
    persist(new Snapshot(current.epoch, current.operation, current.owner, current.ownerDatabase, current.membership,
        current.digest, restoredGeneration, current.participants, current.databases, Decision.COMMIT, false));
  }

  /**
   * COMMIT 的路由/目标资格或 ABORT 的路由撤销已成功应用后，持久记录本地完成。
   * 完成落盘后调用方才能释放写保护；失败仍保持保护。
   */
  public synchronized void applied(long epoch, String operation) throws IOException {
    checkIdentity(epoch, operation);
    if (current.decision == Decision.PENDING) throw new IOException("Recovery decision is still unknown");
    if (!current.finished) persist(current.with(current.decision, true));
  }

  /** 协调者收齐原参与者本地完成回包后记录；接收者只可凭已核验的该标记传播。 */
  public synchronized void allApplied(long epoch, String operation) throws IOException {
    checkIdentity(epoch, operation);
    if (!current.finished || (current.decision != Decision.COMMIT && current.decision != Decision.ABORT))
      throw new IOException("H2 recovery is not locally complete");
    if (!current.allApplied) persist(new Snapshot(current.epoch, current.operation, current.owner, current.ownerDatabase,
        current.membership, current.digest, current.restoredGeneration, current.participants, current.databases,
        current.decision, true, true));
  }

  public synchronized Snapshot snapshot() throws IOException { check(); return current; }
  public String getIdentity() { return identity; }

  /** 记录不可用、未知决定或决定尚未应用时，都必须保留写保护。 */
  public synchronized boolean requiresFence() { return unavailable || closed || !current.finished; }

  private void checkIdentity(long epoch, String operation) throws IOException {
    check();
    if (epoch != current.epoch || !current.operation.equals(operation) || current.decision == Decision.NONE)
      throw new IOException("Stale activation identity");
  }

  private void check() throws IOException {
    if (unavailable || closed) throw new IOException("Recovery activation intent is unavailable");
  }

  private void persist(Snapshot next) throws IOException {
    Path temporary = null;
    try {
      temporary = Files.createTempFile(file.getParent(), "h2-activation-", ".pending");
      try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.WRITE)) { write(out, encode(identity, next)); }
      Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      H2RecoveryFiles.forceDirectory(file.getParent());
      current = next;
    } catch (IOException | RuntimeException | Error failure) {
      unavailable = true;
      if (temporary != null) try { Files.deleteIfExists(temporary); }
      catch (IOException | RuntimeException cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  private Snapshot load() throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 131072)
      throw new IOException("Invalid recovery activation intent");
    Properties p = new Properties();
    try {
      p.load(new ByteArrayInputStream(Files.readAllBytes(file)));
      if (!"3".equals(p.getProperty("version")) || !identity.equals(p.getProperty("identity")))
        throw new IOException("Recovery activation identity mismatch");
      long epoch = Long.parseLong(p.getProperty("epoch"));
      Decision decision = Decision.valueOf(p.getProperty("decision"));
      String operation = p.getProperty("operation");
      String owner = p.getProperty("owner");
      String ownerDatabase = p.getProperty("ownerDatabase");
      String membership = p.getProperty("membership");
      String digest = p.getProperty("digest");
      long restoredGeneration = Long.parseLong(p.getProperty("restoredGeneration"));
      String finished = p.getProperty("finished");
      String allApplied = p.getProperty("allApplied");
      if (!"true".equals(finished) && !"false".equals(finished)) throw new IOException("Invalid completion marker");
      if (!"true".equals(allApplied) && !"false".equals(allApplied)) throw new IOException("Invalid global completion marker");
      if ("true".equals(allApplied) && (!"true".equals(finished) || decision == Decision.NONE || decision == Decision.PENDING))
        throw new IOException("Premature global completion marker");
      int count = Integer.parseInt(p.getProperty("members"));
      if (count < 0 || count > 64 || epoch < 0) throw new IOException("Invalid activation record bounds");
      Set<String> members = new TreeSet<>();
      for (int i = 0; i < count; i++) {
        String member = p.getProperty("member." + i); text(member);
        if (!members.add(member)) throw new IOException("Duplicate recovery participant");
      }
      int databaseCount = Integer.parseInt(p.getProperty("databases"));
      if (databaseCount < 0 || databaseCount > 64) throw new IOException("Invalid stable participant count");
      Set<String> databases = new TreeSet<>();
      for (int i = 0; i < databaseCount; i++) {
        String database = p.getProperty("database." + i); text(database);
        if (!databases.add(database)) throw new IOException("Duplicate stable recovery participant");
      }
      if (!databases.isEmpty() && (databases.size() != members.size() || !databases.contains(ownerDatabase)))
        throw new IOException("Invalid stable recovery participants");
      if (decision == Decision.NONE) {
        if (epoch != 0 || !"".equals(operation) || !"".equals(owner) || !"".equals(digest)
            || !"".equals(ownerDatabase) || !"".equals(membership) || restoredGeneration != 0
            || count != 0 || databaseCount != 0 || !"true".equals(finished)) throw new IOException("Invalid empty activation record");
      } else {
        text(operation); text(owner); text(ownerDatabase); text(membership);
        if (epoch == 0 || !members.contains(owner) || digest == null || (!digest.isEmpty() && !digest.matches("[0-9a-f]{64}"))
            || restoredGeneration < 0 || (decision == Decision.COMMIT && (digest.isEmpty() || restoredGeneration == 0))
            || (decision != Decision.COMMIT && restoredGeneration != 0)
            || (decision == Decision.PENDING && "true".equals(finished)))
          throw new IOException("Invalid activation record fields");
      }
      return new Snapshot(epoch, operation, owner, ownerDatabase, membership, digest, restoredGeneration,
          members, databases, decision, Boolean.parseBoolean(finished), Boolean.parseBoolean(allApplied));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IOException("Malformed recovery activation intent", e);
    }
  }

  private static Properties encode(String identity, Snapshot record) {
    Properties p = new Properties();
    p.setProperty("version", "3"); p.setProperty("identity", identity);
    p.setProperty("epoch", Long.toString(record.epoch));
    p.setProperty("operation", record.operation); p.setProperty("owner", record.owner);
    p.setProperty("ownerDatabase", record.ownerDatabase); p.setProperty("membership", record.membership);
    p.setProperty("restoredGeneration", Long.toString(record.restoredGeneration));
    p.setProperty("digest", record.digest); p.setProperty("decision", record.decision.name());
    p.setProperty("finished", Boolean.toString(record.finished));
    p.setProperty("allApplied", Boolean.toString(record.allApplied));
    p.setProperty("members", Integer.toString(record.participants.size()));
    int index = 0;
    for (String member : record.participants) p.setProperty("member." + index++, member);
    p.setProperty("databases", Integer.toString(record.databases.size()));
    index = 0;
    for (String database : record.databases) p.setProperty("database." + index++, database);
    return p;
  }

  private static void write(FileChannel out, Properties p) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); p.store(bytes, "H2 activation intent");
    ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
    while (buffer.hasRemaining()) out.write(buffer);
    out.force(true);
  }

  private static void text(String value) {
    if (value == null || value.isEmpty() || value.length() > 256 || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid recovery identity");
  }

  /** 只关闭记录持有权；调用方停止时另行处理实际写锁，未决状态保留到下次启动。 */
  @Override public synchronized void close() throws IOException {
    if (closed) return;
    closed = true;
    try { ownership.release(); } finally { channel.close(); }
  }

  /** 可安全发布的不可变快照；不包含 SQL、密码或数据库地址列表。 */
  public static final class Snapshot implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public final long epoch;
    public final String operation;
    public final String owner;
    public final String ownerDatabase;
    public final String membership;
    public final String digest;
    public final long restoredGeneration;
    public final Set<String> participants;
    public final Set<String> databases;
    public final Decision decision;
    public final boolean finished;
    public final boolean allApplied;
    private Snapshot(long epoch, String operation, String owner, String ownerDatabase, String membership,
        String digest, long restoredGeneration, Set<String> members,
        Decision decision, boolean finished) {
      this(epoch, operation, owner, ownerDatabase, membership, digest, restoredGeneration, members, Collections.emptySet(), decision, finished);
    }
    private Snapshot(long epoch, String operation, String owner, String ownerDatabase, String membership,
        String digest, long restoredGeneration, Set<String> members, Set<String> databases,
        Decision decision, boolean finished) {
      this(epoch, operation, owner, ownerDatabase, membership, digest, restoredGeneration, members, databases, decision, finished, false);
    }
    private Snapshot(long epoch, String operation, String owner, String ownerDatabase, String membership,
        String digest, long restoredGeneration, Set<String> members, Set<String> databases,
        Decision decision, boolean finished, boolean allApplied) {
      this.epoch = epoch; this.operation = operation; this.owner = owner; this.digest = digest;
      this.ownerDatabase = ownerDatabase; this.membership = membership; this.restoredGeneration = restoredGeneration;
      this.participants = Collections.unmodifiableSet(new TreeSet<>(members));
      this.databases = Collections.unmodifiableSet(new TreeSet<>(databases));
      this.decision = decision; this.finished = finished;
      this.allApplied = allApplied;
    }
    private Snapshot with(Decision next, boolean applied) {
      return new Snapshot(epoch, operation, owner, ownerDatabase, membership, digest, restoredGeneration, participants, databases, next, applied, allApplied);
    }
  }
}
