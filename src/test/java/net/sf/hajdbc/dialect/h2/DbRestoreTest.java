package net.sf.hajdbc.dialect.h2;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import net.sf.hajdbc.Database;
import net.sf.hajdbc.codec.Decoder;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.InOrder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 恢复 SQL 顺序及失败关闭，使用专属测试 JDBC URL，不连接真实数据库。 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class DbRestoreTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final Driver driver = mock(Driver.class);
  private final Database database = mock(Database.class);
  private final Decoder decoder = mock(Decoder.class);
  private final Connection execution = mock(Connection.class), verification = mock(Connection.class);
  private final Statement statement = mock(Statement.class);
  private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
  private final ResultSet tables = mock(ResultSet.class);
  private String url;
  private File script;
  private DbRestore restore;

  @Before public void configure() throws Exception {
    url = "jdbc:h2-restore-test:" + java.util.UUID.randomUUID();
    DriverManager.registerDriver(driver);
    when(database.getLocation()).thenReturn(url);
    when(database.getUser()).thenReturn("test-user");
    when(database.decodePassword(decoder)).thenReturn("");
    when(driver.connect(eq(url), any(Properties.class))).thenReturn(execution, verification);
    when(execution.createStatement()).thenReturn(statement);
    when(verification.getMetaData()).thenReturn(metadata);
    when(metadata.getTables(any(), any(), anyString(), any(String[].class))).thenReturn(tables);
    when(tables.next()).thenReturn(true, false);
    when(tables.getString("TABLE_SCHEM")).thenReturn("PUBLIC");
    script = temporary.newFile("input's.dump");
    String backup = temporary.getRoot().toPath().resolve("backup's.dump").toString();
    restore = new DbRestore() { @Override String getBakPath(String location) { return backup; } };
  }

  @After public void deregister() throws Exception { DriverManager.deregisterDriver(driver); }

  @Test public void onlyRecoveryLocationDisablesNetworkTimeoutWithoutRewritingOtherSettings() {
    assertEquals("jdbc:h2:mem:test;NETWORK_TIMEOUT=0", DbRestore.restoreLocation("jdbc:h2:mem:test"));
    assertEquals("jdbc:h2:tcp://localhost/db;NETWORK_TIMEOUT=0;AUTO_RECONNECT=TRUE",
        DbRestore.restoreLocation("jdbc:h2:tcp://localhost/db;network_timeout=60000;AUTO_RECONNECT=TRUE"));
    String init = "jdbc:h2:mem:test;INIT=CREATE TABLE A(ID INT)\\;CALL 'NETWORK_TIMEOUT=42';NETWORK_TIMEOUT=20";
    assertEquals(init.substring(0, init.length() - 2) + "0", DbRestore.restoreLocation(init));
    String escaped = "jdbc:h2:mem:test;INIT=CALL 'a'\\;NETWORK_TIMEOUT=42";
    assertEquals(escaped + ";NETWORK_TIMEOUT=0", DbRestore.restoreLocation(escaped));
    assertEquals("jdbc:h2:mem:test;INIT=CALL 'a'\\\\;NETWORK_TIMEOUT=0",
        DbRestore.restoreLocation("jdbc:h2:mem:test;INIT=CALL 'a'\\\\;NETWORK_TIMEOUT=42"));
    assertEquals(url, DbRestore.restoreLocation(url));
  }

  @Test public void executionDisablesSocketTimeoutButVerificationRetainsOriginalLocation() throws Exception {
    String original = "jdbc:h2:tcp://localhost/db;NETWORK_TIMEOUT=60000";
    String physical = "jdbc:h2:tcp://localhost/db;NETWORK_TIMEOUT=0";
    when(database.getLocation()).thenReturn(original);
    when(driver.connect(eq(physical), any(Properties.class))).thenReturn(execution);
    when(driver.connect(eq(original), any(Properties.class))).thenReturn(verification);
    assertTrue(restore.restore(database, decoder, script, true));
    verify(driver).connect(eq(physical), any(Properties.class));
    verify(driver).connect(eq(original), any(Properties.class));
    assertEquals(original, database.getLocation());
  }

  @Test public void successKeepsExclusiveThroughImportThenChecksIndependentConnection() throws Exception {
    assertTrue(restore.restore(database, decoder, script, true));
    InOrder ordered = inOrder(statement, execution, verification, tables);
    ordered.verify(execution).setAutoCommit(false);
    ordered.verify(statement).execute("SET EXCLUSIVE 1");
    ordered.verify(statement).execute(contains("SCRIPT TO  '"));
    ordered.verify(statement).execute("DROP ALL OBJECTS");
    ordered.verify(statement).execute(contains("RUNSCRIPT FROM '"));
    ordered.verify(execution).commit();
    ordered.verify(statement).close();
    ordered.verify(execution).close();
    ordered.verify(verification).getMetaData();
    ordered.verify(tables).close();
    ordered.verify(verification).close();
    verify(statement).execute(contains("backup''s.dump"));
    verify(statement).execute(contains("input''s.dump"));
    verify(statement, never()).execute("SET EXCLUSIVE 2");
    verify(database).setSyncing(false);
    assertFalse(script.exists());
  }

  @Test public void exclusiveFailureNeverBacksUpOrDrops() throws Exception {
    doThrow(new SQLException("exclusive")).when(statement).execute("SET EXCLUSIVE 1");
    assertFalse(restore.restore(database, decoder, script, true));
    verify(statement, never()).execute(startsWith("SCRIPT TO"));
    verify(statement, never()).execute("DROP ALL OBJECTS");
    verify(statement, never()).execute(startsWith("RUNSCRIPT"));
    failedAndClosed();
  }

  @Test public void backupFailureNeverDrops() throws Exception {
    doThrow(new SQLException("backup")).when(statement).execute(startsWith("SCRIPT TO"));
    assertFalse(restore.restore(database, decoder, script, true));
    verify(statement, never()).execute("DROP ALL OBJECTS");
    verify(statement, never()).execute(startsWith("RUNSCRIPT"));
    failedAndClosed();
  }

  @Test public void dropFailureNeverImports() throws Exception {
    doThrow(new SQLException("drop")).when(statement).execute("DROP ALL OBJECTS");
    assertFalse(restore.restore(database, decoder, script, true));
    verify(statement, never()).execute(startsWith("RUNSCRIPT"));
    failedAndClosed();
  }

  @Test public void importFailureClosesWithoutReportingSuccess() throws Exception {
    doThrow(new SQLException("import")).when(statement).execute(startsWith("RUNSCRIPT"));
    assertFalse(restore.restore(database, decoder, script, true));
    failedAndClosed();
  }

  @Test public void statementCloseFailurePreventsSuccessAndConnectionStillCloses() throws Exception {
    doThrow(new SQLException("statement close")).when(statement).close();
    assertFalse(restore.restore(database, decoder, script, true));
    failedAndClosed();
  }

  @Test public void failedCommitNeverReportsSuccessOrStartsIndependentVerification() throws Exception {
    doThrow(new SQLException("commit")).when(execution).commit();
    assertFalse(restore.restore(database, decoder, script, true));
    failedAndClosed();
  }

  @Test public void failedProgressPersistenceBeforeDropPreventsDestructiveSql() throws Exception {
    java.io.IOException failure = new java.io.IOException("journal persistence failed");
    DbRestore.Result result = restore.restoreDetailed(database, decoder, script, true,
        stage -> { if (stage == DbRestore.Stage.DROP) throw failure; });
    assertFalse(result.success);
    assertEquals(DbRestore.Stage.DROP, result.stage);
    assertSame(failure, result.failure);
    verify(statement, never()).execute("DROP ALL OBJECTS");
    verify(statement, never()).execute(startsWith("RUNSCRIPT"));
    failedAndClosed();
  }

  @Test public void systemTablesOnlyFailIndependentVerificationAndKeepInput() throws Exception {
    when(tables.next()).thenReturn(true, true, false);
    when(tables.getString("TABLE_SCHEM")).thenReturn("INFORMATION_SCHEMA", "PG_CATALOG");
    assertFalse(restore.restore(database, decoder, script, true));
    verify(verification).close();
    verify(tables).close();
    assertTrue(script.exists());
    verify(database).setSyncing(false);
  }

  @Test public void legacyPublicEntryRetainsItsExistingFailedInputCleanup() throws Exception {
    doThrow(new SQLException("drop")).when(statement).execute("DROP ALL OBJECTS");
    assertFalse(restore.restore(database, decoder, script));
    assertFalse(script.exists());
    verify(execution).close();
    verify(database).setSyncing(false);
  }

  @Test public void successiveBackupsCannotReuseOrOverwriteThePreviousPath() throws Exception {
    String location = "jdbc:h2:tcp://localhost/" + temporary.getRoot().getAbsolutePath().replace('\\', '/') + "/database";
    DbRestore paths = new DbRestore();
    String first = paths.getBakPath(location);
    java.nio.file.Files.write(java.nio.file.Paths.get(first), "safe old backup".getBytes("UTF-8"));
    String second = paths.getBakPath(location);
    assertNotEquals(first, second);
    assertTrue(new File(first).getName().matches("database_backup_[0-9]{14}_[0-9a-f-]{36}\\.dump"));
    assertEquals("safe old backup", new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(first)), "UTF-8"));
  }

  @Test public void pinnedFirstBackupSurvivesMoreThanTenNewAttemptBackups() throws Exception {
    String location = "jdbc:h2:tcp://localhost/" + temporary.getRoot().getAbsolutePath().replace('\\', '/') + "/database";
    java.nio.file.Path pinned = temporary.getRoot().toPath().resolve("database_backup_00000000000000.dump");
    java.nio.file.Files.write(pinned, "pre-drop original".getBytes("UTF-8"));
    DbRestore paths = new DbRestore(pinned.toString());
    for (int attempt = 0; attempt < 15; attempt++) {
      java.nio.file.Files.write(java.nio.file.Paths.get(paths.getBakPath(location)), ("attempt-" + attempt).getBytes("UTF-8"));
      assertTrue(java.nio.file.Files.exists(pinned));
    }
    assertEquals("pre-drop original", new String(java.nio.file.Files.readAllBytes(pinned), "UTF-8"));
    File[] kept = temporary.getRoot().listFiles(file -> file.getName().startsWith("database_backup_"));
    assertTrue(kept.length <= DbRestore.MAX_BACKUP_COUNT + 1);
    new DbRestore().getBakPath(location); // 解除保护后，才恢复普通历史保留规则。
    assertFalse(java.nio.file.Files.exists(pinned));
  }

  @Test public void inabilityToPersistBackupProtectionPreventsDrop() throws Exception {
    java.io.IOException root = new java.io.IOException("Cannot persist backup protection");
    DbRestore.Result result = restore.restoreDetailed(database, decoder, script, true, new DbRestore.Progress() {
      public void before(DbRestore.Stage stage) { }
      public void backupCreated(String path) throws java.io.IOException { throw root; }
    });
    assertFalse(result.success); assertSame(root, result.failure); assertEquals(DbRestore.Stage.BACKUP, result.stage);
    verify(statement, never()).execute("DROP ALL OBJECTS"); verify(execution).close();
  }

  @Test public void backupRetentionDoesNotCrossDatabaseNamesContainingTheBackupSeparator() throws Exception {
    for (int i = 0; i < 13; i++) java.nio.file.Files.write(temporary.getRoot().toPath()
        .resolve("database_backup_200001010000" + String.format(java.util.Locale.ROOT, "%02d", i) + ".dump"), new byte[] {1});
    java.nio.file.Path unrelated = temporary.getRoot().toPath().resolve("database_backup_user_backup_notes.dump");
    java.nio.file.Files.write(unrelated, new byte[] {2});
    String location = "jdbc:h2:tcp://localhost/" + temporary.getRoot().getAbsolutePath().replace('\\', '/') + "/database_backup_user";
    new DbRestore().getBakPath(location);
    for (int i = 0; i < 13; i++) assertTrue(java.nio.file.Files.exists(temporary.getRoot().toPath()
        .resolve("database_backup_200001010000" + String.format(java.util.Locale.ROOT, "%02d", i) + ".dump")));
    assertTrue(java.nio.file.Files.exists(unrelated));
  }

  private void failedAndClosed() throws Exception {
    verify(statement).close();
    verify(execution).close();
    verify(driver, times(1)).connect(eq(url), any(Properties.class));
    verify(verification, never()).getMetaData();
    verify(database).setSyncing(false);
    assertTrue(script.exists());
  }
}
