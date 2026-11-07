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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * 一个本机集群的恢复记录集合。目录位于数据库之外，DROP 不会清除隔离状态。
 * 目录只能显式首次建立；启动打开时不修补缺失记录、不从活动库列表重新推导健康。
 */
public final class H2RecoveryStore implements AutoCloseable {
  private final Path directory;
  private final String localDatabase;
  private final FileChannel channel;
  private final FileLock ownership;
  private final Map<String, Target> targets = new LinkedHashMap<>();
  private boolean closed;

  /**
   * 首次迁移的基线必须由启动方在尚未开放服务时提供；普通重启只调用构造函数。
   * 先建目录，再逐个建立记录，最后发布 catalog；中途失败留下不完整目录并拒绝启动。
   */
  public static void initialize(Path directory, String cluster, String localDatabase,
      Map<String, String> identities, Set<String> initiallyActive) throws IOException {
    validate(cluster, localDatabase, identities);
    if (initiallyActive == null || !identities.keySet().containsAll(initiallyActive))
      throw new IOException("Unknown recovery baseline database");
    Files.createDirectory(directory);
    H2RecoveryFiles.forceDirectory(directory.toAbsolutePath().getParent());
    Files.createDirectory(directory.resolve("inputs"));
    Properties catalog = catalog(cluster, localDatabase, identities, initiallyActive);
    for (Map.Entry<String, String> entry : new TreeMap<>(identities).entrySet()) {
      String key = key(entry.getKey());
      H2ActivationIntent.create(directory.resolve(key + ".intent"), entry.getValue());
      H2RestoreJournal.createIsolated(directory.resolve(key + ".journal"), entry.getValue());
    }
    Path pending = directory.resolve("catalog.pending");
    try (FileChannel output = FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      catalog.store(bytes, null);
      ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
      while (buffer.hasRemaining()) output.write(buffer);
      output.force(true);
    }
    Files.move(pending, directory.resolve("catalog"), StandardCopyOption.ATOMIC_MOVE);
    H2RecoveryFiles.forceDirectory(directory);
  }

  /** 打开必须成功读取目录清单及每份已登记记录，不能以空文件或新记录替代。 */
  public H2RecoveryStore(Path directory, String cluster, String localDatabase,
      Map<String, String> identities) throws IOException {
    validate(cluster, localDatabase, identities);
    this.directory = directory.toAbsolutePath().normalize();
    this.localDatabase = localDatabase;
    if (!Files.isDirectory(this.directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing H2 recovery directory");
    channel = FileChannel.open(this.directory.resolve("store.lock"), StandardOpenOption.CREATE,
        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    FileLock lock = null;
    try {
      lock = channel.tryLock();
      if (lock == null) throw new IOException("H2 recovery store is already owned");
      Properties stored = loadCatalog();
      if (!Files.isDirectory(this.directory.resolve("inputs"), LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Missing H2 recovery input directory");
      if (!"1".equals(stored.getProperty("version")) || !cluster.equals(stored.getProperty("cluster"))
          || !localDatabase.equals(stored.getProperty("local"))
          || !Integer.toString(identities.size()).equals(stored.getProperty("count")))
        throw new IOException("H2 recovery catalog identity mismatch");
      for (Map.Entry<String, String> entry : new TreeMap<>(identities).entrySet()) {
        String key = key(entry.getKey());
        String baseline = stored.getProperty(key + ".active");
        if (!entry.getKey().equals(stored.getProperty(key + ".id"))
            || !entry.getValue().equals(stored.getProperty(key + ".identity"))
            || !("true".equals(baseline) || "false".equals(baseline)))
          throw new IOException("H2 recovery target identity mismatch");
        targets.put(entry.getKey(), openTarget(key, entry.getValue(), Boolean.parseBoolean(baseline)));
      }
    } catch (IOException | RuntimeException | Error failure) {
      closeTargets(failure);
      if (lock != null) try { lock.release(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
      try { channel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
      if (failure instanceof OverlappingFileLockException) throw new IOException("H2 recovery store is already owned", failure);
      throw failure;
    }
    ownership = lock;
  }

  private Target openTarget(String key, String identity, boolean baseline) throws IOException {
    H2ActivationIntent intent = new H2ActivationIntent(directory.resolve(key + ".intent"), identity);
    try {
      return new Target(identity, baseline, intent, new H2RestoreJournal(directory.resolve(key + ".journal"), identity));
    } catch (IOException | RuntimeException | Error failure) {
      try { intent.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  public synchronized Target target(String database) throws IOException {
    if (closed) throw new IOException("H2 recovery store is closed");
    Target target = targets.get(database);
    if (target == null) throw new IOException("Unknown H2 recovery target");
    return target;
  }

  public synchronized Set<String> databases() { return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(targets.keySet())); }
  public String getLocalDatabase() { return localDatabase; }

  /** 同一操作有固定文件名，不把对端路径按本机 OS 重新拼接。根目录始终由接收端决定。 */
  public synchronized Path inputPath(String database, String operation) throws IOException {
    target(database);
    if (operation == null || operation.isEmpty() || operation.length() > 256) throw new IOException("Invalid H2 recovery operation");
    return directory.resolve("inputs").resolve(key(database) + "-" + key(operation) + ".dump.zip");
  }

  /** 仅回收本目标已命名的旧制品组，保留当前记录和最近十组，不扫描其他目录。 */
  public synchronized void pruneInputs(String database) throws IOException {
    Target target = target(database);
    String prefix = key(database) + "-";
    String retained = target.intent.snapshot().operation;
    String current = retained.isEmpty() ? "" : key(retained);
    Map<String, java.util.List<Path>> groups = new java.util.HashMap<>();
    Map<String, Long> modified = new java.util.HashMap<>();
    try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(directory.resolve("inputs"), prefix + "*")) {
      for (Path file : files) {
        String suffix = file.getFileName().toString().substring(prefix.length());
        if (!suffix.matches("[0-9a-f]{64}\\.dump(?:\\.zip(?:\\.tmp)?)?")) continue;
        java.nio.file.attribute.BasicFileAttributes attrs = Files.readAttributes(file,
            java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) continue;
        String operation = suffix.substring(0, 64);
        if (operation.equals(current)) continue;
        groups.computeIfAbsent(operation, ignored -> new java.util.ArrayList<>()).add(file);
        modified.put(operation, Math.max(modified.getOrDefault(operation, Long.MIN_VALUE), attrs.lastModifiedTime().toMillis()));
      }
    }
    java.util.List<String> sorted = new java.util.ArrayList<>(groups.keySet());
    sorted.sort(java.util.Comparator.<String>comparingLong(modified::get).reversed().thenComparing(java.util.Comparator.naturalOrder()));
    for (int i = 10; i < sorted.size(); i++) for (Path file : groups.get(sorted.get(i))) Files.deleteIfExists(file);
  }

  /** 只有原始基线或本地已应用的明确 COMMIT 可通过；文件故障始终拒绝。 */
  public synchronized boolean isEligible(String database) {
    if (closed) return false;
    try {
      Target target = target(database);
      H2ActivationIntent.Snapshot record = target.intent.snapshot();
      if (record.decision == H2ActivationIntent.Decision.NONE) return target.initiallyActive;
      return record.finished && record.decision == H2ActivationIntent.Decision.COMMIT
          && (!localDatabase.equals(database) || target.journal.isReady());
    } catch (IOException | RuntimeException failure) { return false; }
  }

  /** 物理执行未结束时必须保留所有记录及文件锁，调用方可以在收口后再次关闭。 */
  @Override public synchronized void close() throws IOException {
    if (closed) return;
    for (Target target : targets.values())
      if (target.journal.isRunning()) throw new IOException("H2 physical restore is still running");
    closed = true;
    IOException failure = new IOException("Unable to close H2 recovery store");
    closeTargets(failure);
    try { ownership.release(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
    try { channel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
    if (failure.getSuppressed().length > 0) throw failure;
  }

  private void closeTargets(Throwable failure) {
    for (Target target : targets.values()) {
      try { target.journal.close(); } catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
      try { target.intent.close(); } catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
    }
  }

  private Properties loadCatalog() throws IOException {
    Path catalog = directory.resolve("catalog");
    if (!Files.isRegularFile(catalog, LinkOption.NOFOLLOW_LINKS) || Files.size(catalog) > 65536)
      throw new IOException("Missing or invalid H2 recovery catalog");
    Properties result = new Properties();
    try { result.load(new ByteArrayInputStream(Files.readAllBytes(catalog))); }
    catch (IllegalArgumentException malformed) { throw new IOException("Malformed H2 recovery catalog", malformed); }
    return result;
  }

  private static Properties catalog(String cluster, String local, Map<String, String> identities, Set<String> active) {
    Properties p = new Properties();
    p.setProperty("version", "1"); p.setProperty("cluster", cluster); p.setProperty("local", local);
    p.setProperty("count", Integer.toString(identities.size()));
    for (Map.Entry<String, String> entry : identities.entrySet()) {
      String key = key(entry.getKey());
      p.setProperty(key + ".id", entry.getKey());
      p.setProperty(key + ".identity", entry.getValue());
      p.setProperty(key + ".active", Boolean.toString(active.contains(entry.getKey())));
    }
    return p;
  }

  private static void validate(String cluster, String local, Map<String, String> identities) throws IOException {
    if (cluster == null || cluster.isEmpty() || cluster.length() > 256 || local == null || identities == null
        || identities.isEmpty() || identities.size() > 64 || !identities.containsKey(local))
      throw new IOException("Invalid H2 recovery store identity");
    for (Map.Entry<String, String> entry : identities.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getKey().length() > 256
          || entry.getValue() == null || !entry.getValue().matches("[0-9a-f]{64}"))
        throw new IOException("Invalid H2 recovery database identity");
    }
  }

  /** 文件名不使用数据库 id 原文，避免路径分隔符和配置内容泄露。 */
  private static String key(String database) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
          .digest(database.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder key = new StringBuilder(64);
      for (byte value : digest) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
      return key.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }

  /** 每个目标共享一个串行监视器；SQL 执行必须在退出该监视器后进行。 */
  public static final class Target {
    public final String identity;
    public final H2ActivationIntent intent;
    public final H2RestoreJournal journal;
    private final boolean initiallyActive;
    private Target(String identity, boolean baseline, H2ActivationIntent intent, H2RestoreJournal journal) {
      this.identity = identity; this.initiallyActive = baseline; this.intent = intent; this.journal = journal;
    }
  }
}
