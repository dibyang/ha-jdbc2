package net.sf.hajdbc.dialect.h2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 使用真实目录、记录及 OS 文件锁核验启动装载，不能靠内存活动列表覆盖失败。 */
public class H2RecoveryStoreTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static final String SHA = String.join("", Collections.nCopies(64, "a"));
  private final Map<String, String> identities = new LinkedHashMap<>();

  public H2RecoveryStoreTest() {
    identities.put("source", SHA);
    identities.put("target", SHA.replace('a', 'b'));
  }

  private Path initialize() throws IOException {
    Path path = temporary.getRoot().toPath().resolve("recovery");
    H2RecoveryStore.initialize(path, "cluster", "target", identities, Collections.singleton("source"));
    return path;
  }
  private H2RecoveryStore open(Path path) throws IOException { return new H2RecoveryStore(path, "cluster", "target", identities); }

  @Test public void firstMigrationBaselineIsLoadedButInactiveTargetIsNotMadeHealthy() throws Exception {
    Path path = initialize();
    try (H2RecoveryStore store = open(path)) {
      assertEquals(identities.keySet(), store.databases());
      assertTrue(store.isEligible("source"));
      assertFalse(store.isEligible("target"));
      assertFalse(store.isEligible("unknown"));
      assertEquals(H2RestoreJournal.State.FAILED, store.target("target").journal.getState());
      assertEquals(H2ActivationIntent.Decision.NONE, store.target("target").intent.snapshot().decision);
    }
    reject(() -> H2RecoveryStore.initialize(path, "cluster", "target", identities, identities.keySet()));
    try (H2RecoveryStore store = open(path)) { assertFalse(store.isEligible("target")); }
  }

  @Test public void restoreRequiresCommitAndRestartRequiresReapplyingTheSameDurableProof() throws Exception {
    Path path = initialize();
    try (H2RecoveryStore store = open(path)) {
      H2RecoveryStore.Target target = store.target("target");
      prepare(target);
      assertFalse(store.isEligible("target"));
      target.journal.openSession(1, "operation");
      target.journal.finish(target.journal.begin(1, "operation", 0, "operation", SHA), true);
      assertFalse(store.isEligible("target"));
      target.intent.commit(1, "operation", 1);
      target.journal.confirmCommitted(target.intent);
      assertFalse(store.isEligible("target"));
      target.intent.applied(1, "operation");
      assertTrue(store.isEligible("target"));
    }
    try (H2RecoveryStore store = open(path)) {
      H2RecoveryStore.Target target = store.target("target");
      assertEquals(H2ActivationIntent.Decision.COMMIT, target.intent.snapshot().decision);
      assertEquals(H2RestoreJournal.State.RESTORED, target.journal.getState());
      assertFalse("A reboot must rebuild local activation before admission", store.isEligible("target"));
      target.journal.confirmCommitted(target.intent);
      assertTrue(store.isEligible("target"));
    }
  }

  @Test public void abortCannotBeOverriddenByOldActiveBaselineOrNewActiveInput() throws Exception {
    Path path = initialize();
    try (H2RecoveryStore store = open(path)) {
      H2RecoveryStore.Target source = store.target("source");
      prepare(source);
      source.intent.decide(1, "operation", H2ActivationIntent.Decision.ABORT);
      source.intent.applied(1, "operation");
      assertFalse(store.isEligible("source"));
    }
    try (H2RecoveryStore store = open(path)) { assertFalse(store.isEligible("source")); }
  }

  @Test public void deletingAnyRecordedFileCannotCreateAFreshHealthyBaseline() throws Exception {
    Path path = initialize();
    Path victim;
    try (java.util.stream.Stream<Path> files = Files.list(path)) {
      victim = files.filter(file -> file.toString().endsWith(".journal")).findFirst().get();
    }
    Files.delete(victim);
    reject(() -> { try (H2RecoveryStore ignored = open(path)) { fail(); } });
    assertFalse("Opening must never recreate a missing record", Files.exists(victim));
    reject(() -> H2RecoveryStore.initialize(path, "cluster", "target", identities, identities.keySet()));
  }

  @Test public void partialMigrationCorruptCatalogAndDifferentConfigurationFailClosed() throws Exception {
    Path path = temporary.getRoot().toPath().resolve("partial");
    Files.createDirectory(path);
    reject(() -> { try (H2RecoveryStore ignored = open(path)) { fail(); } });
    Path complete = initialize();
    Map<String, String> changed = new LinkedHashMap<>(identities);
    changed.put("target", SHA.replace('a', 'c'));
    reject(() -> { try (H2RecoveryStore ignored = new H2RecoveryStore(complete, "cluster", "target", changed)) { fail(); } });
    reject(() -> { try (H2RecoveryStore ignored = new H2RecoveryStore(complete, "other", "target", identities)) { fail(); } });
    Files.write(complete.resolve("catalog"), "version=\\uZZZZ".getBytes("ISO-8859-1"));
    reject(() -> { try (H2RecoveryStore ignored = open(complete)) { fail(); } });
  }

  @Test public void runningPhysicalWorkPreventsStoreOwnershipFromBeingReleased() throws Exception {
    Path path = initialize();
    H2RecoveryStore store = open(path);
    try {
      H2RecoveryStore.Target target = store.target("target");
      target.journal.openSession(1, "operation");
      H2RestoreJournal.Ticket ticket = target.journal.begin(1, "operation", 0, "operation", SHA);
      reject(store::close);
      reject(() -> { try (H2RecoveryStore ignored = open(path)) { fail(); } });
      target.journal.invalidateSession(1, "operation");
      target.journal.finish(ticket, true);
      assertFalse(store.isEligible("target"));
    } finally { store.close(); }
    try (H2RecoveryStore reopened = open(path)) {
      assertEquals(H2RestoreJournal.State.FAILED, reopened.target("target").journal.getState());
    }
  }

  @Test public void failedOpenReleasesAlreadyOpenedRecordLocks() throws Exception {
    Path path = initialize();
    try (H2RecoveryStore first = open(path)) {
      reject(() -> { try (H2RecoveryStore ignored = open(path)) { fail(); } });
      assertTrue(first.isEligible("source"));
    }
    Path missing;
    try (java.util.stream.Stream<Path> files = Files.list(path)) {
      missing = files.filter(file -> file.toString().endsWith(".intent")).reduce((a, b) -> b).get();
    }
    byte[] saved = Files.readAllBytes(missing);
    Files.delete(missing);
    reject(() -> { try (H2RecoveryStore ignored = open(path)) { fail(); } });
    Files.write(missing, saved);
    try (H2RecoveryStore reopened = open(path)) { assertEquals(2, reopened.databases().size()); }
  }

  @Test public void inputRetentionKeepsTheCurrentOperationAndTenOldGroupsWithoutCrossingTargets() throws Exception {
    Path path = initialize();
    try (H2RecoveryStore store = open(path)) {
      prepare(store.target("target"));
      Path current = store.inputPath("target", "operation"); Files.write(current, new byte[] {1});
      Path other = store.inputPath("source", "other"); Files.write(other, new byte[] {2});
      for (int i = 0; i < 13; i++) {
        Path zip = store.inputPath("target", "old-" + i);
        Path script = java.nio.file.Paths.get(zip.toString().substring(0, zip.toString().length() - 4));
        for (Path artifact : Arrays.asList(zip, script)) {
          Files.write(artifact, new byte[] {3});
          Files.setLastModifiedTime(artifact, java.nio.file.attribute.FileTime.fromMillis(1000L + i));
        }
      }
      Path unrelated = current.resolveSibling("unrelated.dump.zip"); Files.write(unrelated, new byte[] {4});
      store.pruneInputs("target");
      assertTrue(Files.exists(current)); assertTrue(Files.exists(other)); assertTrue(Files.exists(unrelated));
      for (int i = 0; i < 13; i++) assertEquals(i >= 3, Files.exists(store.inputPath("target", "old-" + i)));
      try (java.util.stream.Stream<Path> files = Files.list(current.getParent())) { assertEquals(23, files.count()); }
    }
  }

  private static void prepare(H2RecoveryStore.Target target) throws IOException {
    target.intent.prepare(1, "operation", "source-member", "source", "views", SHA,
        new java.util.TreeSet<>(Arrays.asList("source-member", "target-member")));
  }
  @FunctionalInterface private interface Attempt { void run() throws Exception; }
  private static void reject(Attempt attempt) throws Exception {
    try { attempt.run(); fail("Unsafe store operation was accepted"); }
    catch (IOException expected) { }
  }
}
