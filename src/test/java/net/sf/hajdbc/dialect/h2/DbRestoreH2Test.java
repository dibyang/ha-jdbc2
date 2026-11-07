package net.sf.hajdbc.dialect.h2;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import net.sf.hajdbc.Database;
import net.sf.hajdbc.codec.Decoder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 当前真实 H2 依赖的备份/清库/导入校验，全部为独立内存数据库。 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class DbRestoreH2Test {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test public void realRestoreSupportsQuotedPathsAndReplacesOldTables() throws Exception {
    run("CREATE TABLE NEW_DATA(ID INT); INSERT INTO NEW_DATA VALUES(7);", true, true);
  }

  @Test public void realPartialImportIsFailureAndPreservesBackupAndInput() throws Exception {
    run("CREATE TABLE NEW_DATA(ID INT); INSERT INTO NEW_DATA VALUES(7); THIS_IS_NOT_SQL;", false, true);
  }

  @Test public void realEmptyImportIsNotSuccess() throws Exception {
    run("-- no user tables\n", false, false);
  }

  private void run(String content, boolean expectedSuccess, boolean hasNewData) throws Exception {
    String url = "jdbc:h2:mem:restore_" + java.util.UUID.randomUUID().toString().replace("-", "");
    Database database = mock(Database.class);
    Decoder decoder = mock(Decoder.class);
    when(database.getLocation()).thenReturn(url);
    when(database.getUser()).thenReturn("sa");
    when(database.decodePassword(decoder)).thenReturn("");
    File folder = temporary.newFolder("quoted's folder");
    File input = new File(folder, "input's.dump");
    File backup = new File(folder, "backup's.dump");
    Files.write(input.toPath(), content.getBytes(StandardCharsets.UTF_8));
    DbRestore restore = new DbRestore() { @Override String getBakPath(String location) { return backup.getPath(); } };
    try (Connection observer = DriverManager.getConnection(url, "sa", ""); Statement statement = observer.createStatement()) {
      statement.execute("CREATE TABLE OLD_DATA(ID INT)");
      statement.execute("INSERT INTO OLD_DATA VALUES(3)");
      assertEquals(expectedSuccess, restore.restore(database, decoder, input, true));
      assertTrue(backup.exists());
      assertEquals(!expectedSuccess, input.exists());
      // 失败后已执行的 DROP 不会回滚，必须由隔离协议阻止该目标上线。
      try { statement.executeQuery("SELECT * FROM OLD_DATA"); fail(); } catch (SQLException expected) { }
      if (hasNewData) {
        try (java.sql.ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM NEW_DATA")) {
          assertTrue(result.next()); assertEquals(expectedSuccess ? 1 : 0, result.getInt(1)); assertFalse(result.next());
        }
      }
      // 原独占连接已被关闭，既有连接仍可使用，未被 EXCLUSIVE 2 驱逐。
      assertFalse(observer.isClosed());
      verify(database).setSyncing(false);
    }
  }

  @Test public void emptySourceCannotProduceAnAutomaticReplacementDump() throws Exception {
    String url = "jdbc:h2:mem:empty_source_" + java.util.UUID.randomUUID().toString().replace("-", "");
    Database database = mock(Database.class);
    Decoder decoder = mock(Decoder.class);
    when(database.getLocation()).thenReturn(url);
    when(database.getUser()).thenReturn("sa");
    when(database.decodePassword(decoder)).thenReturn("");
    File input = temporary.newFile("source.dump");
    try (Connection ignored = DriverManager.getConnection(url, "sa", "")) {
      try { new H2Dialect().dump(null, database, decoder, input, false); fail(); }
      catch (SQLException expected) { assertTrue(expected.getMessage().contains("no user tables")); }
      assertEquals(0, input.length());
      assertFalse(new File(input.getPath() + ".zip").exists());
    }
  }

  @Test public void realDumpZipAndRestoreRoundTripPreservesRowsAndQuotedPaths() throws Exception {
    String suffix = java.util.UUID.randomUUID().toString().replace("-", "");
    String sourceUrl = "jdbc:h2:mem:source_" + suffix, targetUrl = "jdbc:h2:mem:target_" + suffix;
    Decoder decoder = mock(Decoder.class);
    Database source = mock(Database.class), target = mock(Database.class);
    when(source.getLocation()).thenReturn(sourceUrl); when(target.getLocation()).thenReturn(targetUrl);
    when(source.getUser()).thenReturn("sa"); when(target.getUser()).thenReturn("sa");
    when(source.decodePassword(decoder)).thenReturn(""); when(target.decodePassword(decoder)).thenReturn("");
    File folder = temporary.newFolder("dump's folder");
    File input = new File(folder, "database's.dump"), backup = new File(folder, "backup's.dump");
    try (Connection sourceConnection = DriverManager.getConnection(sourceUrl, "sa", "");
         Connection targetConnection = DriverManager.getConnection(targetUrl, "sa", "");
         Statement sourceStatement = sourceConnection.createStatement();
         Statement targetStatement = targetConnection.createStatement()) {
      sourceStatement.execute("CREATE TABLE BUSINESS_DATA(ID INT PRIMARY KEY)");
      sourceStatement.execute("INSERT INTO BUSINESS_DATA VALUES(7)");
      targetStatement.execute("CREATE TABLE OLD_DATA(ID INT)");
      new H2Dialect().dump(null, source, decoder, input, false);
      File zip = new File(input.getPath() + ".zip");
      assertTrue(zip.length() > 0);
      Files.delete(input.toPath());
      DbRestore restore = new DbRestore() { @Override String getBakPath(String location) { return backup.getPath(); } };
      assertTrue(restore.restore(target, decoder, input, true));
      assertFalse(input.exists()); assertFalse(zip.exists()); assertTrue(backup.exists());
      try (java.sql.ResultSet rows = targetStatement.executeQuery("SELECT ID FROM BUSINESS_DATA")) {
        assertTrue(rows.next()); assertEquals(7, rows.getInt(1)); assertFalse(rows.next());
      }
      try (java.sql.ResultSet rows = sourceStatement.executeQuery("SELECT ID FROM BUSINESS_DATA")) {
        assertTrue(rows.next()); assertEquals(7, rows.getInt(1)); assertFalse(rows.next());
      }
    }
  }

  @Test(timeout = 20000) public void realTcpFileRestoreUsesProductionBackupPathAndKeepsOtherConnectionOpen() throws Exception {
    org.h2.tools.Server server = org.h2.tools.Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
    try {
      File folder = temporary.newFolder("tcp's directory");
      String dbPath = new File(folder, "database").getAbsolutePath().replace('\\', '/');
      String url = "jdbc:h2:tcp://localhost:" + server.getPort() + "/" + dbPath + ";AUTO_RECONNECT=TRUE";
      Database database = mock(Database.class);
      Decoder decoder = mock(Decoder.class);
      when(database.getLocation()).thenReturn(url);
      when(database.getUser()).thenReturn("sa");
      when(database.decodePassword(decoder)).thenReturn("");
      File input = new File(folder, "input's.dump");
      Files.write(input.toPath(), "CREATE TABLE RECOVERED(ID INT); INSERT INTO RECOVERED VALUES(11);".getBytes(StandardCharsets.UTF_8));
      try (Connection existing = DriverManager.getConnection(url, "sa", ""); Statement statement = existing.createStatement()) {
        statement.execute("CREATE TABLE OLD_DATA(ID INT)");
        statement.execute("INSERT INTO OLD_DATA VALUES(5)");
        assertTrue(new DbRestore().restore(database, decoder, input, true));
        assertFalse(existing.isClosed());
        assertTrue(existing.getAutoCommit());
        try (java.sql.ResultSet rows = statement.executeQuery("SELECT ID FROM RECOVERED")) {
          assertTrue(rows.next()); assertEquals(11, rows.getInt(1)); assertFalse(rows.next());
        }
        File[] backups = folder.listFiles(file -> file.getName().startsWith("database_backup_") && file.getName().endsWith(".dump"));
        assertNotNull(backups);
        assertEquals(1, backups.length);
        assertTrue(new String(Files.readAllBytes(backups[0].toPath()), StandardCharsets.UTF_8).contains("OLD_DATA"));
        assertFalse(input.exists());
      }
    } finally { server.stop(); }
  }

  @Test(timeout = 10000) public void realQueryTimeoutReleasesExclusiveConnectionAndKeepsFailedInput() throws Exception {
    String url = "jdbc:h2:mem:timed_" + java.util.UUID.randomUUID().toString().replace("-", "") + ";MAX_QUERY_TIMEOUT=1000";
    Database database = mock(Database.class);
    Decoder decoder = mock(Decoder.class);
    when(database.getLocation()).thenReturn(url);
    when(database.getUser()).thenReturn("sa");
    when(database.decodePassword(decoder)).thenReturn("");
    File input = temporary.newFile("input.dump");
    Files.write(input.toPath(), ("CREATE TABLE NEW_DATA(ID BIGINT); INSERT INTO NEW_DATA "
        + "SELECT SUM(MOD(X,97)) FROM SYSTEM_RANGE(1,1000000000000);").getBytes(StandardCharsets.UTF_8));
    File backup = new File(temporary.getRoot(), "timeout-backup.dump");
    DbRestore restore = new DbRestore() { @Override String getBakPath(String location) { return backup.getPath(); } };
    try (Connection observer = DriverManager.getConnection(url, "sa", ""); Statement check = observer.createStatement()) {
      check.execute("CREATE TABLE OLD_DATA(ID INT)");
      DbRestore.Result result = restore.restoreDetailed(database, decoder, input, true, stage -> { });
      assertFalse(result.success);
      assertEquals(DbRestore.Stage.IMPORT, result.stage);
      assertTrue(result.failure instanceof SQLException);
      assertEquals(57014, ((SQLException) result.failure).getErrorCode());
      assertTrue(backup.exists());
      assertTrue(input.exists());
      verify(database).setSyncing(false);
      // 独占会话已关闭，原有连接未被驱逐，也不再被失败恢复暂停。
      assertFalse(observer.isClosed());
      check.execute("CREATE TABLE AFTER_FAILED_RESTORE(ID INT)");
    }
  }

  @Test public void realSqlAndDurableProgressDoNotGrantActivationWithoutCommitProof() throws Exception {
    String url = "jdbc:h2:mem:journal_sql_" + java.util.UUID.randomUUID().toString().replace("-", "");
    Database database = mock(Database.class);
    Decoder decoder = mock(Decoder.class);
    when(database.getLocation()).thenReturn(url);
    when(database.getUser()).thenReturn("sa"); when(database.decodePassword(decoder)).thenReturn("");
    File input = temporary.newFile("input.dump"), backup = new File(temporary.getRoot(), "backup.dump");
    Files.write(input.toPath(), "CREATE TABLE NEW_DATA(ID INT); INSERT INTO NEW_DATA VALUES(3);".getBytes(StandardCharsets.UTF_8));
    java.nio.file.Path record = temporary.getRoot().toPath().resolve("restore.state");
    H2RestoreJournal.createIsolated(record, "cluster/target");
    try (Connection observer = DriverManager.getConnection(url, "sa", "");
         Statement statement = observer.createStatement(); H2RestoreJournal journal = new H2RestoreJournal(record, "cluster/target")) {
      statement.execute("CREATE TABLE OLD_DATA(ID INT)");
      journal.openSession(1, "session");
      H2RestoreJournal.Ticket ticket = journal.begin(1, "session", 0, "op", String.join("", java.util.Collections.nCopies(64, "a")));
      DbRestore restore = new DbRestore() { @Override String getBakPath(String location) { return backup.getPath(); } };
      DbRestore.Result result = restore.restoreDetailed(database, decoder, input, true, stage -> {
        journal.progress(ticket, stage.name());
        if (stage == DbRestore.Stage.DROP)
          assertTrue(new String(Files.readAllBytes(record), StandardCharsets.ISO_8859_1).contains("stage=DROP"));
      });
      assertTrue(result.success);
      assertEquals(DbRestore.Stage.VERIFY, result.stage);
      journal.finish(ticket, result.success);
      assertEquals("VERIFY", journal.getStage());
      assertEquals(H2RestoreJournal.State.RESTORED, journal.getState());
      assertFalse(journal.isReady());
    }
  }
}
