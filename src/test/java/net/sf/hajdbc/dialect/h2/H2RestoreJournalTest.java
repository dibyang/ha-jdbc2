package net.sf.hajdbc.dialect.h2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 使用真实文件及文件锁验证恢复记录，不运行清库 SQL。 */
public class H2RestoreJournalTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static final String SHA = String.join("", java.util.Collections.nCopies(64, "a"));

  private Path create() throws IOException {
    Path file = temporary.getRoot().toPath().resolve("restore.state");
    H2RestoreJournal.createIsolated(file, "cluster/target");
    return file;
  }

  @Test public void acceptedOperationIsPersistedBeforeCallerCanExecute() throws Exception {
    Path path = create();
    try (H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) {
      journal.openSession(1, "session");
      H2RestoreJournal.Ticket ticket = journal.begin(1, "session", 0, "operation", SHA);
      String stored = new String(Files.readAllBytes(path), "ISO-8859-1");
      assertTrue(stored.contains("state=APPLYING"));
      assertTrue(stored.contains("generation=1"));
      assertNotNull(ticket);
      assertNull(journal.begin(1, "session", 0, "operation", SHA));
      assertTrue(journal.isRunning());
      journal.finish(ticket, true);
      assertTrue(journal.isRestored(1, "session", 1, "operation", SHA));
      assertNull(journal.begin(1, "session", 0, "operation", SHA));
    }
  }

  @Test public void lateSuccessAfterTimeoutCannotRemoveIsolation() throws Exception {
    Path path = create();
    try (H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) {
      journal.openSession(1, "old");
      H2RestoreJournal.Ticket old = journal.begin(1, "old", 0, "old-op", SHA);
      journal.invalidateSession(1, "old");
      reject(() -> journal.begin(1, "old", 1, "late", SHA));
      reject(() -> journal.openSession(2, "new"));
      reject(journal::close);
      journal.finish(old, true);
      assertEquals(H2RestoreJournal.State.FAILED, journal.getState());
      assertFalse(journal.isRestored(1, "old", 1, "old-op", SHA));
      reject(() -> journal.openSession(1, "old"));
      journal.openSession(2, "new");
      H2RestoreJournal.Ticket next = journal.begin(2, "new", 1, "new-op", SHA);
      reject(() -> journal.finish(old, true));
      assertTrue(journal.isRunning());
      journal.finish(next, true);
      assertTrue(journal.isRestored(2, "new", 2, "new-op", SHA));
    }
  }

  @Test public void restartRetainsImportFactButInvalidatesSessionAndKeepsGeneration() throws Exception {
    Path path = create();
    try (H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) {
      journal.openSession(1, "before");
      journal.finish(journal.begin(1, "before", 0, "old-op", SHA), true);
    }
    try (H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) {
      assertEquals(1, journal.getGeneration());
      assertEquals(H2RestoreJournal.State.RESTORED, journal.getState());
      assertFalse(journal.isReady());
      assertFalse(journal.isRestored(1, "before", 1, "old-op", SHA));
      reject(() -> journal.begin(1, "before", 0, "old-op", SHA));
      reject(() -> journal.openSession(1, "before"));
      journal.openSession(2, "after");
      reject(() -> journal.begin(2, "after", 0, "old-op", SHA));
      H2RestoreJournal.Ticket next = journal.begin(2, "after", 1, "new-op", SHA);
      journal.finish(next, true);
      assertEquals(2, journal.getGeneration());
    }
  }

  @Test public void staleFutureAndConflictingRequestsCannotStartExecution() throws Exception {
    Path path = create();
    try (H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) {
      journal.openSession(1, "session");
      H2RestoreJournal.Ticket first = journal.begin(1, "session", 0, "one", SHA);
      reject(() -> journal.begin(1, "session", 1, "two", SHA));
      reject(() -> journal.begin(1, "session", 0, "one", SHA.replace('a', 'b')));
      journal.finish(first, false);
      reject(() -> journal.begin(1, "session", 0, "two", SHA));
      reject(() -> journal.begin(1, "session", 2, "two", SHA));
      assertEquals(1, journal.getGeneration());
      assertFalse(journal.isRunning());
    }
  }

  @Test public void lateRequestAndReleaseCannotAffectNewSessionWithSameName() throws Exception {
    Path path = create();
    try (H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) {
      journal.openSession(1, "same-name");
      journal.invalidateSession(1, "same-name");
      journal.openSession(2, "same-name");
      // 尚未接受过恢复，generation 仍然相等；必须靠会话代次挡住迟到请求。
      reject(() -> journal.begin(1, "same-name", 0, "late-operation", SHA));
      journal.invalidateSession(1, "same-name");
      H2RestoreJournal.Ticket current = journal.begin(2, "same-name", 0, "current-operation", SHA);
      journal.finish(current, true);
      assertTrue(journal.isRestored(2, "same-name", 1, "current-operation", SHA));
      assertFalse(journal.isRestored(1, "same-name", 1, "current-operation", SHA));
    }
  }

  @Test public void anotherInstanceCannotOwnTheSameTargetUntilClose() throws Exception {
    Path path = create();
    try (H2RestoreJournal first = new H2RestoreJournal(path, "cluster/target")) {
      reject(() -> { try (H2RestoreJournal other = new H2RestoreJournal(path, "cluster/target")) { fail(); } });
    }
    try (H2RestoreJournal reopened = new H2RestoreJournal(path, "cluster/target")) {
      assertEquals(0, reopened.getGeneration());
    }
  }

  @Test public void missingCorruptAndForeignRecordsFailClosed() throws Exception {
    Path path = temporary.getRoot().toPath().resolve("restore.state");
    reject(() -> { try (H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) { fail(); } });
    H2RestoreJournal.createIsolated(path, "other/target");
    reject(() -> { try (H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) { fail(); } });
    Files.write(path, "broken".getBytes("UTF-8"));
    reject(() -> { try (H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) { fail(); } });
    Files.delete(path);
    H2RestoreJournal.createIsolated(path, "cluster/target");
    try (H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) { assertEquals(0, journal.getGeneration()); }
  }

  @Test public void failedPersistenceNeverHandsOutAnExecutionTicket() throws Exception {
    Path path = create();
    try (H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) {
      journal.openSession(1, "session");
      Files.delete(path);
      Files.createDirectory(path);
      Files.createFile(path.resolve("cannot-replace-nonempty-directory"));
      reject(() -> journal.begin(1, "session", 0, "operation", SHA));
      assertFalse(journal.isRunning());
      assertFalse(journal.isRestored(1, "session", 1, "operation", SHA));
      reject(() -> journal.openSession(2, "other"));
    }
  }

  @Test public void onlyMatchingDurableCommitCanConfirmReadyAndRestartRequiresItAgain() throws Exception {
    Path path = create();
    Path intentPath = temporary.getRoot().toPath().resolve("target.intent");
    H2ActivationIntent.create(intentPath, "cluster/target");
    try (H2ActivationIntent intent = new H2ActivationIntent(intentPath, "cluster/target")) {
      intent.prepare(1, "op", "source", SHA, new java.util.HashSet<>(java.util.Arrays.asList("source", "target")));
      try (H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) {
        journal.openSession(1, "session");
        journal.finish(journal.begin(1, "session", 0, "op", SHA), true);
        reject(() -> journal.confirmCommitted(intent));
        assertFalse(journal.isReady());
        intent.commit(1, "op", 1);
        journal.confirmCommitted(intent);
        journal.confirmCommitted(intent);
        assertTrue(journal.isReady());
        journal.invalidateSession(1, "session");
        assertTrue(journal.isReady());
      }
      try (H2RestoreJournal restarted = new H2RestoreJournal(path, "cluster/target")) {
        assertFalse(restarted.isReady());
        assertEquals(H2RestoreJournal.State.RESTORED, restarted.getState());
        restarted.confirmCommitted(intent);
        assertTrue(restarted.isReady());
      }
    }
  }

  @Test public void wrongRecoveryEpochCannotAuthorizeAnOldImport() throws Exception {
    Path path = create();
    Path intentPath = temporary.getRoot().toPath().resolve("target.intent");
    H2ActivationIntent.create(intentPath, "cluster/target");
    try (H2ActivationIntent intent = new H2ActivationIntent(intentPath, "cluster/target");
         H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) {
      journal.openSession(1, "old-session");
      journal.finish(journal.begin(1, "old-session", 0, "same-op", SHA), true);
      journal.invalidateSession(1, "old-session");
      intent.prepare(2, "same-op", "source", SHA, new java.util.HashSet<>(java.util.Arrays.asList("source", "target")));
      intent.commit(2, "same-op", 1);
      reject(() -> journal.confirmCommitted(intent));
      assertFalse(journal.isReady());
    }
  }

  @Test public void stageIsDurableAndLateProgressCannotTouchNewOperation() throws Exception {
    Path path = create();
    try (H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) {
      journal.openSession(1, "first");
      H2RestoreJournal.Ticket first = journal.begin(1, "first", 0, "first", SHA);
      journal.progress(first, "DROP");
      assertTrue(new String(Files.readAllBytes(path), "ISO-8859-1").contains("stage=DROP"));
      journal.invalidateSession(1, "first");
      reject(() -> journal.progress(first, "IMPORT"));
      journal.finish(first, true);
      assertEquals(H2RestoreJournal.State.FAILED, journal.getState());
      journal.openSession(2, "second");
      H2RestoreJournal.Ticket second = journal.begin(2, "second", 1, "second", SHA);
      reject(() -> journal.progress(first, "VERIFY"));
      assertEquals("ACCEPTED", journal.getStage());
      journal.finish(second, false);
    }
  }

  @Test public void physicalExitReleasesRunningOwnershipEvenWhenJournalBecameUnavailable() throws Exception {
    Path path = create();
    try (H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) {
      journal.openSession(1, "session");
      H2RestoreJournal.Ticket ticket = journal.begin(1, "session", 0, "op", SHA);
      Files.delete(path);
      Files.createDirectory(path);
      Files.createFile(path.resolve("block-replace"));
      reject(() -> journal.progress(ticket, "DROP"));
      assertTrue(journal.isRunning());
      reject(() -> journal.finish(ticket, false));
      assertFalse(journal.isRunning());
      journal.close();
      Path lock = path.resolveSibling(path.getFileName() + ".lock");
      try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(lock, java.nio.file.StandardOpenOption.WRITE);
           java.nio.channels.FileLock ownership = channel.tryLock()) {
        assertNotNull(ownership);
      }
    }
  }

  @Test(timeout = 20000) public void abruptJvmExitLeavesApplyingIsolatedOnRestart() throws Exception {
    Path path = create();
    String executable = java.nio.file.Paths.get(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    String classpath = new java.io.File(CrashAfterAccept.class.getProtectionDomain().getCodeSource()
        .getLocation().toURI()).getPath() + java.io.File.pathSeparator
        + new java.io.File(H2RestoreJournal.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
    Process child = new ProcessBuilder(executable, "-cp", classpath, CrashAfterAccept.class.getName(), path.toString())
        .redirectErrorStream(true).redirectOutput(temporary.newFile("child.log")).start();
    try {
      assertTrue("Child must exit without waiting for cleanup", child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(23, child.exitValue());
      assertTrue(new String(Files.readAllBytes(path), "ISO-8859-1").contains("state=APPLYING"));
      try (H2RestoreJournal restarted = new H2RestoreJournal(path, "cluster/target")) {
        assertEquals(H2RestoreJournal.State.FAILED, restarted.getState());
        assertEquals(1, restarted.getGeneration());
        reject(() -> restarted.begin(1, "dead-process", 0, "operation", SHA));
        restarted.openSession(2, "new-process");
        reject(() -> restarted.begin(2, "new-process", 0, "operation", SHA));
        H2RestoreJournal.Ticket next = restarted.begin(2, "new-process", 1, "fresh-operation", SHA);
        restarted.finish(next, true);
        assertTrue(restarted.isRestored(2, "new-process", 2, "fresh-operation", SHA));
      }
    } finally {
      if (child.isAlive()) child.destroyForcibly();
      assertTrue("Test helper must be reaped", child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  /** 独立 JVM 在 APPLYING 已落盘后直接退出，不调用 close/finally。 */
  public static class CrashAfterAccept {
    public static void main(String[] arguments) throws Exception {
      H2RestoreJournal journal = new H2RestoreJournal(java.nio.file.Paths.get(arguments[0]), "cluster/target");
      journal.openSession(1, "dead-process");
      journal.begin(1, "dead-process", 0, "operation", String.join("", java.util.Collections.nCopies(64, "a")));
      Runtime.getRuntime().halt(23);
    }
  }

  @Test public void firstBackupPinSurvivesFailuresAndRestartUntilMatchingCommit() throws Exception {
    Path path = create(); Path first = temporary.getRoot().toPath().resolve("first.dump");
    Files.write(first, "pre-drop contents".getBytes("UTF-8"));
    try (H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) {
      journal.openSession(1, "one"); H2RestoreJournal.Ticket old = journal.begin(1, "one", 0, "one", SHA);
      reject(() -> journal.pinBackup(old, first.toString()));
      journal.progress(old, "BACKUP"); journal.pinBackup(old, first.toString());
      journal.finish(old, false); journal.invalidateSession(1, "one");
      journal.openSession(2, "two"); H2RestoreJournal.Ticket next = journal.begin(2, "two", 1, "two", SHA);
      journal.progress(next, "BACKUP"); reject(() -> journal.pinBackup(old, first.toString()));
      journal.pinBackup(next, temporary.getRoot().toPath().resolve("later.dump").toString());
      assertEquals(first.toString(), journal.getPinnedBackup()); journal.finish(next, true);
    }
    try (H2RestoreJournal journal = new H2RestoreJournal(path, "cluster/target")) {
      assertEquals(first.toString(), journal.getPinnedBackup()); assertFalse(journal.isReady());
      Path intentFile = temporary.getRoot().toPath().resolve("pin.intent"); H2ActivationIntent.create(intentFile, "cluster/target");
      try (H2ActivationIntent intent = new H2ActivationIntent(intentFile, "cluster/target")) {
        intent.prepare(2, "two", "source", SHA, new java.util.HashSet<>(java.util.Arrays.asList("source", "target")));
        intent.commit(2, "two", 2); journal.confirmCommitted(intent);
        assertEquals("", journal.getPinnedBackup()); assertTrue(journal.isReady());
      }
    }
    assertEquals("pre-drop contents", new String(Files.readAllBytes(first), "UTF-8"));
  }

  private interface IOAction { void run() throws Exception; }
  private static void reject(IOAction action) throws Exception {
    try { action.run(); fail("Expected failure to preserve isolation"); }
    catch (IOException expected) { }
  }
}
