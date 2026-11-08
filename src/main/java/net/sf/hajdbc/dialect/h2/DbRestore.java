package net.sf.hajdbc.dialect.h2;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.codec.Decoder;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.sql.AbstractDatabase;
import net.sf.hajdbc.util.Files;
import net.sf.hajdbc.util.StopWatch;
import net.sf.hajdbc.util.ZipUtils;

import java.io.File;
import java.io.FileFilter;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class DbRestore {
  private final java.nio.file.Path pinnedBackup;
  private final boolean protectedRestore;
  public DbRestore() { this(null, false); }
  DbRestore(String pinnedBackup) { this(pinnedBackup, true); }
  private DbRestore(String pinnedBackup, boolean protectedRestore) {
    this.pinnedBackup = pinnedBackup == null || pinnedBackup.isEmpty() ? null : Paths.get(pinnedBackup).toAbsolutePath().normalize();
    this.protectedRestore = protectedRestore;
  }
  static final Logger logger = LoggerFactory.getLogger(H2RunScriptCommand.class);
  static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
  public static final int MAX_BACKUP_COUNT = 10;
  public static final String EXT_DUMP = ".dump";
  public static final String EXT_ZIP = ".zip";

  public boolean restore(Database database, Decoder decoder, File file) {
    return restore(database, decoder, file, false);
  }

  /** 新恢复协议保留失败输入；旧公开入口的清理策略保持兼容。 */
  boolean restore(Database database, Decoder decoder, File file, boolean preserveFailedInput) {
    return restoreDetailed(database, decoder, file, preserveFailedInput, stage -> { }).success;
  }

  /** 阶段回调先于对应 SQL 执行，持久记录失败时不得继续下一条破坏性语句。 */
  Result restoreDetailed(Database database, Decoder decoder, File file, boolean preserveFailedInput, Progress progress) {
    File zipFile = null;
    boolean restored = false;
    Stage stage = Stage.INPUT;
    try {
      progress.before(stage);
      zipFile = Paths.get(file.getPath() + EXT_ZIP).toFile();
      database.setSyncing(true);
      if (!file.exists() && zipFile.exists()) {
        StopWatch stopWatch = StopWatch.createStarted();
        ZipUtils.decompressFile(zipFile.getPath(), file.getParentFile().getPath());
        stopWatch.stop();
        logger.log(Level.INFO, "H2 unzip dump file use time {0} to {1}", stopWatch.toString(), zipFile.getPath());
      }
      if (file.exists()) {
        StopWatch stopWatch = StopWatch.createStarted();
        stage = Stage.CONNECT; progress.before(stage);
        final String password = database.decodePassword(decoder);
        try (Connection connection = DriverManager.getConnection(restoreLocation(database.getLocation()), database.getUser(), password); Statement statTarget = connection.createStatement()) {
          statTarget.setQueryTimeout(60);
          // 禁用此恢复会话的自动重连：重连后的新会话不再持有原独占资格。
          // DDL 仍不可回滚，这不是把清库和导入包装为原子事务。
          connection.setAutoCommit(false);
          stage = Stage.EXCLUSIVE; progress.before(stage);
          // 客户端重启不能证明服务端 SQL 已退出；由同一数据库会话持有物理互斥。
          // 模式 1 不驱逐既有连接，关闭本连接自动释放，失败绝不能跳过独占直接 DROP。
          statTarget.execute("SET EXCLUSIVE 1");
          stage = Stage.BACKUP; progress.before(stage);
          String bakPath = getBakPath(database.getLocation());
          //更改为数据库sql文件
          statTarget.execute("SCRIPT TO  '" + bakPath.replace("'", "''") + "'");
          progress.backupCreated(bakPath);
          logger.log(Level.INFO, "H2 backup old data use time {0} to {1}", stopWatch.toString(), bakPath);
          stage = Stage.DROP; progress.before(stage);
          statTarget.execute("DROP ALL OBJECTS");
          stage = Stage.IMPORT; progress.before(stage);
          statTarget.execute("RUNSCRIPT FROM '" + file.getPath().replace("'", "''") + "'");
          stage = Stage.COMMIT; progress.before(stage);
          connection.commit();
          stopWatch.stop();
          logger.log(Level.INFO, "H2 Run Script use time {0} from {1}", stopWatch.toString(), file.getPath());
        }
        // 独占连接已关闭；通过另一个真实连接核对导入结果和连接关闭状态。
        stage = Stage.VERIFY; progress.before(stage);
        try (Connection verification = DriverManager.getConnection(database.getLocation(), database.getUser(), password)) {
          H2RestoreDatabaseChecks.requireUserTables(verification, "restored target");
        }
        restored = true;
        return new Result(true, stage, null);
      }
      return new Result(false, stage, new java.io.IOException("H2 restore input does not exist"));
    } catch (Exception e) {
      logger.log(Level.WARN, e);
      return new Result(false, stage, e);
    } finally {
      try {
        if (restored || !preserveFailedInput) {
          if (file != null) Files.delete(file);
          if (zipFile != null) Files.delete(zipFile);
        }
      } finally {
        database.setSyncing(false);
      }
    }
  }

  /** 仅恢复执行连接等待物理完成；H2 读超时会丢失 transfer，普通 close 不再能释放服务端独占。 */
  static String restoreLocation(String location) {
    if (!location.startsWith("jdbc:h2:")) return location;
    StringBuilder result = new StringBuilder(location.length() + 20);
    boolean replaced = false;
    int start = 0;
    for (int i = 0; i <= location.length(); i++) {
      // H2 URL 的反斜杠转义下一个字符，INIT 等参数内的分号不是设置边界。
      if (i + 1 < location.length() && location.charAt(i) == '\\') { i++; continue; }
      if (i == location.length() || location.charAt(i) == ';') {
        String setting = location.substring(start, i);
        if (start > 0 && setting.regionMatches(true, 0, "NETWORK_TIMEOUT=", 0, 16)) {
          result.append("NETWORK_TIMEOUT=0");
          replaced = true;
        } else result.append(setting);
        if (i < location.length()) result.append(';');
        start = i + 1;
      }
    }
    if (!replaced) result.append(";NETWORK_TIMEOUT=0");
    return result.toString();
  }

  enum Stage { INPUT, CONNECT, EXCLUSIVE, BACKUP, DROP, IMPORT, COMMIT, VERIFY }
  @FunctionalInterface interface Progress {
    void before(Stage stage) throws java.io.IOException;
    default void backupCreated(String path) throws java.io.IOException { }
  }
  /** 本机保留原始根因；远端回包只发送阶段，不把任意异常对象作为协议字段。 */
  static final class Result {
    final boolean success;
    final Stage stage;
    final Exception failure;
    Result(boolean success, Stage stage, Exception failure) { this.success = success; this.stage = stage; this.failure = failure; }
  }

  String getBakPath(String location) throws java.io.IOException {
    location = H2RecoveryRuntime.databasePath(location).toAbsolutePath().normalize().toString();

    String backupFlag = "_backup_";
    // 同秒内的下一次失败恢复不能用空库备份覆盖故障前备份。
    String bakPath = location + backupFlag + LocalDateTime.now().format(formatter)
        + "_" + java.util.UUID.randomUUID().toString() + EXT_DUMP;
    File bakFile = new File(bakPath);
    String namePrefix = new File(location).getName() + backupFlag;
    File dbDir = bakFile.getParentFile();
    if (!dbDir.exists()) {
      dbDir.mkdirs();
    }
    File[] files = dbDir.listFiles(f -> f.getName().startsWith(namePrefix)
        && f.getName().substring(namePrefix.length()).matches("[0-9]{14}(?:_[0-9a-f-]{36})?\\.dump"));
    if (files != null) {
      List<File> fileList = Arrays.stream(files).filter(f -> pinnedBackup == null || !f.toPath().toAbsolutePath().normalize().equals(pinnedBackup))
          .sorted((f1, f2) -> f2.getName().compareTo(f1.getName())).skip(pinnedBackup == null ? MAX_BACKUP_COUNT : MAX_BACKUP_COUNT - 1).collect(Collectors.toList());
      for (File old : fileList) {
        if (old.delete()) logger.log(Level.INFO, "H2 remove backup {0}", old.getPath());
        else if (protectedRestore) throw new java.io.IOException("Unable to prune H2 restore backup");
      }
    }
    return bakPath;
  }
}
