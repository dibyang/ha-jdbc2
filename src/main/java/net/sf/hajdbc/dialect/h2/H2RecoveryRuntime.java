package net.sf.hajdbc.dialect.h2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.lock.distributed.DistributedLockManager;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.state.distributed.DistributedStateManager;
import net.sf.hajdbc.state.sync.H2RecoveryCommand;

/** 把持久保护装入现有状态通道生命周期，不创建调度器，也不改变仲裁规则。 */
public final class H2RecoveryRuntime<Z, D extends Database<Z>> {
  public static final String DIRECTORY_PROPERTY = "ha-jdbc.h2-recovery-dir";
  private static final Logger LOG = LoggerFactory.getLogger(H2RecoveryRuntime.class);
  private final H2RecoveryCoordinator<Z, D> coordinator;

  private H2RecoveryRuntime(H2RecoveryCoordinator<Z, D> coordinator) { this.coordinator = coordinator; }

  /** 本地状态已启动、状态通道尚未接收消息时安装；任何本地记录故障都使启动失败。 */
  public static <Z, D extends Database<Z>> H2RecoveryRuntime<Z, D> install(
      DistributedStateManager<Z, D> state, Collection<D> configured) throws Exception {
    DatabaseCluster<Z, D> cluster = state.getDatabaseCluster();
    if (!(cluster.getDialect() instanceof H2Dialect)) return null;
    if (configured == null || configured.isEmpty() || !(cluster.getLockManager() instanceof DistributedLockManager))
      throw new IOException("H2 recovery requires configured databases and the distributed lock manager");
    D local = cluster.getLocalDatabase();
    if (local == null) throw new IOException("Missing local H2 recovery database");
    Map<String, String> identities = new LinkedHashMap<>();
    for (D database : configured) {
      String name = databaseName(database.getLocation());
      if (name.startsWith("mem:")) throw new IOException("Durable H2 recovery requires a persistent database");
      String identity = digest(cluster.getId() + "\n" + database.getId() + "\n" + name);
      if (identities.put(database.getId(), identity) != null) throw new IOException("Duplicate H2 database identity");
    }
    Path base = baseDirectory(local.getLocation());
    H2RecoveryFiles.createDirectories(base);
    if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid H2 recovery base directory");
    String namespace = digest(cluster.getId() + "\n" + local.getId());
    Path directory = base.resolve(namespace + ".store");
    // 标记留在 store 之外，单独丢失 store 不能触发一次新的健康基线迁移。
    Path marker = base.resolve(namespace + ".initialized");
    byte[] expected = (namespace + "\n").getBytes(StandardCharsets.US_ASCII);
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      try (FileChannel file = FileChannel.open(marker, StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
        ByteBuffer bytes = ByteBuffer.wrap(expected);
        while (bytes.hasRemaining()) file.write(bytes);
        file.force(true);
      }
      H2RecoveryFiles.forceDirectory(base);
      // 首次升级只声明尚无本协议失败历史，不宣称已有业务数据完整；导出仍检查用户表。
      H2RecoveryStore.initialize(directory, cluster.getId(), local.getId(), identities, identities.keySet());
    }
    if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) != expected.length
        || !java.util.Arrays.equals(expected, Files.readAllBytes(marker))) throw new IOException("Missing H2 recovery initialization marker");
    H2RecoveryStore store = new H2RecoveryStore(directory, cluster.getId(), local.getId(), identities);
    try {
      DistributedLockManager locks = (DistributedLockManager) cluster.getLockManager();
      H2RecoveryParticipant<Z, D> participant = new H2RecoveryParticipant<>(store, state, locks, local.getId());
      participant.restoreWriteFences();
      H2RecoveryCoordinator<Z, D> coordinator = new H2RecoveryCoordinator<>(store, participant, state, locks);
      state.setExtContext(H2RecoveryCommand.Handler.class.getName(), participant);
      state.setExtContext(H2RecoveryCoordinator.class.getName(), coordinator);
      H2RecoveryRuntime<Z, D> runtime = new H2RecoveryRuntime<>(coordinator);
      state.setExtContext(H2RecoveryRuntime.class.getName(), runtime);
      return runtime;
    } catch (Exception | Error failure) {
      try { store.close(); } catch (Exception | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  /** 网络暂不可读时不开放资格，由随后已有健康轮自动重试；本地文件故障已在 install 拒绝。 */
  public void discover() {
    try { coordinator.discover(); }
    catch (Exception failure) { LOG.log(Level.WARN, failure, "H2 recovery discovery is incomplete; keep admission closed"); }
  }

  public void onHealthRound() {
    try { coordinator.reconcile(); }
    catch (Exception failure) { LOG.log(Level.WARN, failure, "H2 recovery decision remains unresolved"); }
  }

  public void stop() {
    try { coordinator.stop(); }
    catch (IOException failure) { LOG.log(Level.WARN, failure, "H2 recovery stop retains storage until physical work ends"); }
  }

  /** 绝对数据库路径提供跨工作目录重启稳定的默认位置；其他部署显式指定持久目录。 */
  static Path baseDirectory(String location) throws IOException {
    String configured = System.getProperty(DIRECTORY_PROPERTY);
    if (configured != null && !configured.trim().isEmpty()) {
      Path path = Paths.get(configured).normalize();
      if (!path.isAbsolute()) throw new IOException("H2 recovery directory must be absolute");
      return path;
    }
    Path database = databasePath(location);
    if (!database.isAbsolute()) throw new IOException("A persistent H2 recovery directory is required for a non-absolute database path");
    return Paths.get(database.toString() + ".ha-jdbc-recovery");
  }

  static Path databasePath(String location) throws IOException {
    String name = databaseName(location);
    if (name.startsWith("file:")) name = name.substring(5);
    if (name.startsWith("~/") || name.startsWith("~\\")) {
      String home = System.getProperty("user.home");
      if (home == null || home.isEmpty()) throw new IOException("Missing service account home for H2 recovery directory");
      name = Paths.get(home).resolve(name.substring(2)).toString();
    }
    return Paths.get(name).normalize();
  }

  /** 不把 TCP/SSL 主机别名或连接密码当作数据库文件身份的一部分。 */
  static String databaseName(String location) throws IOException {
    if (location == null || !location.startsWith("jdbc:h2:")) throw new IOException("Invalid H2 JDBC location");
    if (location.contains("\\;")) throw new IOException("Escaped H2 database path requires an unambiguous location");
    String name = location.substring("jdbc:h2:".length());
    int options = name.indexOf(';'); if (options >= 0) name = name.substring(0, options);
    if (name.startsWith("tcp://") || name.startsWith("ssl://")) {
      int slash = name.indexOf('/', name.indexOf("://") + 3);
      if (slash < 0) throw new IOException("Missing H2 database path");
      name = name.substring(slash + 1);
    }
    if (name.isEmpty()) throw new IOException("Missing H2 database name");
    return name;
  }

  private static String digest(String value) throws Exception {
    byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder result = new StringBuilder(64);
    for (byte item : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
    return result.toString();
  }
}
