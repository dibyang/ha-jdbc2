package net.sf.hajdbc.dialect.h2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 验证参与者持久决定，不把内存重启或对端离开当成撤销证据。 */
public class H2ActivationIntentTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static final String SHA = String.join("", java.util.Collections.nCopies(64, "a"));
  private static Set<String> members() { return new HashSet<>(Arrays.asList("source-boot", "target-boot")); }
  private Path create() throws Exception {
    Path file = temporary.getRoot().toPath().resolve("target.intent");
    H2ActivationIntent.create(file, "cluster/target");
    return file;
  }

  @Test public void restartPreservesUnknownDecisionAndRequiresWriteFence() throws Exception {
    Path file = create();
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      assertFalse(intent.requiresFence());
      intent.prepare(1, "op", "source-boot", SHA, members());
      assertTrue(intent.requiresFence());
    }
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      assertEquals(H2ActivationIntent.Decision.PENDING, intent.snapshot().decision);
      assertTrue(intent.requiresFence());
      reject(() -> intent.applied(1, "op"));
      reject(() -> intent.prepare(2, "new", "source-boot", SHA, members()));
    }
  }

  @Test public void decisionAloneCannotReleaseFenceAndCommitCannotBeReversed() throws Exception {
    Path file = create();
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      intent.prepare(1, "op", "source-boot", SHA, members());
      intent.commit(1, "op", 1);
      intent.commit(1, "op", 1);
      assertTrue(intent.requiresFence());
      reject(() -> intent.decide(1, "op", H2ActivationIntent.Decision.ABORT));
    }
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      assertEquals(H2ActivationIntent.Decision.COMMIT, intent.snapshot().decision);
      assertTrue(intent.requiresFence());
      intent.applied(1, "op");
      assertFalse(intent.requiresFence());
      intent.applied(1, "op");
    }
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      assertFalse(intent.requiresFence());
      reject(() -> intent.decide(1, "op", H2ActivationIntent.Decision.ABORT));
    }
  }

  @Test public void abortIsDurableAndCannotBecomeLateCommit() throws Exception {
    Path file = create();
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      intent.prepare(1, "op", "source-boot", SHA, members());
      intent.decide(1, "op", H2ActivationIntent.Decision.ABORT);
    }
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      reject(() -> intent.commit(1, "op", 1));
      assertTrue(intent.requiresFence());
      intent.applied(1, "op");
      intent.prepare(2, "next", "source-boot", SHA, members());
      reject(() -> intent.commit(1, "op", 1));
      reject(() -> intent.applied(1, "op"));
      assertEquals("next", intent.snapshot().operation);
      assertTrue(intent.requiresFence());
    }
  }

  @Test public void duplicatePrepareRequiresExactIdentityAndImmutableParticipantSet() throws Exception {
    Path file = create();
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      Set<String> original = members();
      intent.prepare(1, "op", "source-boot", SHA, original);
      original.clear();
      intent.prepare(1, "op", "source-boot", SHA, members());
      assertEquals(2, intent.snapshot().participants.size());
      reject(() -> intent.prepare(1, "op", "target-boot", SHA, members()));
      reject(() -> intent.prepare(1, "op", "source-boot", SHA.replace('a', 'b'), members()));
      reject(() -> intent.prepare(1, "op", "source-boot", SHA, java.util.Collections.singleton("source-boot")));
      try { intent.snapshot().participants.clear(); fail(); }
      catch (UnsupportedOperationException expected) { }
    }
  }

  @Test public void failedCompletionPersistenceKeepsFenceEvenAfterRouteWasApplied() throws Exception {
    Path file = create();
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      intent.prepare(1, "op", "source-boot", SHA, members());
      intent.commit(1, "op", 1);
      Files.delete(file);
      Files.createDirectory(file);
      Files.createFile(file.resolve("block-replace"));
      reject(() -> intent.applied(1, "op"));
      assertTrue(intent.requiresFence());
      reject(intent::snapshot);
    }
  }

  @Test public void missingForeignAndCorruptedRecordsAreNeverTreatedAsEmpty() throws Exception {
    Path file = temporary.getRoot().toPath().resolve("target.intent");
    reject(() -> { try (H2ActivationIntent ignored = new H2ActivationIntent(file, "cluster/target")) { fail(); } });
    H2ActivationIntent.create(file, "different/target");
    reject(() -> { try (H2ActivationIntent ignored = new H2ActivationIntent(file, "cluster/target")) { fail(); } });
    Files.write(file, "broken".getBytes("UTF-8"));
    reject(() -> { try (H2ActivationIntent ignored = new H2ActivationIntent(file, "cluster/target")) { fail(); } });
  }

  @Test public void anotherInstanceCannotOwnSameIntent() throws Exception {
    Path file = create();
    try (H2ActivationIntent first = new H2ActivationIntent(file, "cluster/target")) {
      reject(() -> { try (H2ActivationIntent ignored = new H2ActivationIntent(file, "cluster/target")) { fail(); } });
    }
    try (H2ActivationIntent next = new H2ActivationIntent(file, "cluster/target")) { assertFalse(next.requiresFence()); }
  }

  @Test public void initiatorStableIdentityViewAndRestoredGenerationSurviveRestart() throws Exception {
    Path file = create();
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      intent.prepare(5, "op", "source-boot", "database-A", "state-view/lock-view", "", members());
      reject(() -> intent.commit(5, "op", 3));
      intent.bindInput(5, "op", SHA);
      intent.bindInput(5, "op", SHA);
      // 迟到的原始空摘要准备不能替换已绑定的输入，也不能误认成新操作。
      intent.prepare(5, "op", "source-boot", "database-A", "state-view/lock-view", "", members());
      reject(() -> intent.bindInput(5, "op", SHA.replace('a', 'b')));
      reject(() -> intent.prepare(5, "op", "source-boot", "different-database", "state-view/lock-view", SHA, members()));
      reject(() -> intent.commit(5, "op", 0));
      intent.commit(5, "op", 3);
      reject(() -> intent.commit(5, "op", 4));
    }
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      assertEquals("database-A", intent.snapshot().ownerDatabase);
      assertEquals("source-boot", intent.snapshot().owner);
      assertEquals("state-view/lock-view", intent.snapshot().membership);
      assertEquals(3, intent.snapshot().restoredGeneration);
      assertEquals(SHA, intent.snapshot().digest);
      assertTrue(intent.requiresFence());
    }
  }

  @Test public void abortBeforeExportDoesNotNeedAnInputButCannotLaterCommit() throws Exception {
    Path file = create();
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      intent.prepare(1, "op", "source-boot", "database-A", "views", "", members());
      intent.decide(1, "op", H2ActivationIntent.Decision.ABORT);
      reject(() -> intent.bindInput(1, "op", SHA));
      reject(() -> intent.commit(1, "op", 1));
    }
    try (H2ActivationIntent intent = new H2ActivationIntent(file, "cluster/target")) {
      assertEquals(H2ActivationIntent.Decision.ABORT, intent.snapshot().decision);
      assertEquals("", intent.snapshot().digest);
      assertTrue(intent.requiresFence());
    }
  }

  private interface IOAction { void run() throws Exception; }
  private static void reject(IOAction action) throws Exception {
    try { action.run(); fail("Expected protected failure"); } catch (IOException expected) { }
  }
}
