package net.sf.hajdbc.state.sync;

import java.nio.file.Path;
import java.util.Arrays;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.dialect.h2.H2ActivationIntent;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.distributed.MembershipSnapshot;
import net.sf.hajdbc.distributed.jgroups.AddressMember;
import net.sf.hajdbc.state.distributed.DistributedManager;
import net.sf.hajdbc.state.distributed.StateCommandContext;
import org.jgroups.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 新恢复命令的线协议、会话字段与失败关闭；不调用数据库或网络。 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class H2RecoveryCommandTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static final String SHA = String.join("", java.util.Collections.nCopies(64, "a"));
  private String originalRoots;
  @Before public void saveProperties() { originalRoots = System.getProperty(SyncFilePath.ALLOWED_ROOTS_PROPERTY); }
  @After public void restoreProperties() {
    if (originalRoots == null) System.clearProperty(SyncFilePath.ALLOWED_ROOTS_PROPERTY);
    else System.setProperty(SyncFilePath.ALLOWED_ROOTS_PROPERTY, originalRoots);
  }

  private H2RecoveryCommand.Operation operation() {
    Member source = new AddressMember(UUID.randomUUID()), target = new AddressMember(UUID.randomUUID());
    Member sourceLock = new AddressMember(UUID.randomUUID()), targetLock = new AddressMember(UUID.randomUUID());
    return new H2RecoveryCommand.Operation("target-db", SHA, "source-db", 7, "operation", 3, source, sourceLock,
        new MembershipSnapshot(source, 1, Arrays.asList(source, target)),
        new MembershipSnapshot(sourceLock, 2, Arrays.asList(sourceLock, targetLock)));
  }

  @Test public void everyLiveCommandRoundTripsItsExactOperationAndBothChannelViews() {
    H2RecoveryCommand.Operation operation = operation();
    String script = temporary.getRoot().toPath().resolve("input.dump").toString();
    System.setProperty(SyncFilePath.ALLOWED_ROOTS_PROPERTY, temporary.getRoot().getPath());
    for (H2RecoveryCommand command : Arrays.asList(H2RecoveryCommand.prepare(operation),
        H2RecoveryCommand.bind(operation, SHA), H2RecoveryCommand.restore(operation, SHA, script))) {
      H2RecoveryCommand copy = net.sf.hajdbc.util.Objects.deserialize(net.sf.hajdbc.util.Objects.serialize(command));
      copy.validate();
      assertFalse("Mutating recovery requests must keep sender ordering", copy.isOutOfBand());
      assertEquals(command.action, copy.action);
      assertEquals(operation.ownerState, copy.operation.ownerState);
      assertEquals(operation.ownerLock, copy.operation.ownerLock);
      assertEquals(operation.stateView, copy.operation.stateView);
      assertEquals(operation.lockView, copy.operation.lockView);
      assertEquals(operation.membership(), copy.operation.membership());
      assertEquals(operation.participants(), copy.operation.participants());
      assertEquals(operation.databases, copy.operation.databases);
      assertEquals(7, copy.operation.epoch);
      assertEquals(3, copy.operation.expectedGeneration);
      assertEquals("source-db", copy.operation.ownerDatabase);
    }
  }

  @Test public void onlyReadOnlyEvidenceQueriesBypassSenderOrdering() {
    assertTrue(H2RecoveryCommand.probe("target-db", SHA).isOutOfBand());
    H2RecoveryCommand query = net.sf.hajdbc.util.Objects.deserialize(
        net.sf.hajdbc.util.Objects.serialize(H2RecoveryCommand.query("target-db", SHA)));
    assertTrue(query.isOutOfBand());
    assertFalse("Existing upload commands retain ordered delivery", new UploadedCommand().isOutOfBand());
  }

  @Test public void missingReceiverExplicitlyRejectsRatherThanRunningOldRestore() {
    StateCommandContext context = mock(StateCommandContext.class);
    DatabaseCluster cluster = mock(DatabaseCluster.class);
    when(context.getDatabaseCluster()).thenReturn(cluster);
    H2RecoveryCommand.Reply result = H2RecoveryCommand.probe("target-db", SHA).execute(context);
    assertEquals(H2RecoveryCommand.Status.UNSUPPORTED, result.status);
    assertEquals(1, result.protocol);
    verify(cluster, never()).getDecoder();
    verify(context, never()).execute(any(), any());
  }

  @Test public void installedReceiverIsTheOnlyExecutionPath() {
    StateCommandContext context = mock(StateCommandContext.class);
    DatabaseCluster cluster = mock(DatabaseCluster.class);
    DistributedManager manager = mock(DistributedManager.class);
    H2RecoveryCommand.Handler handler = mock(H2RecoveryCommand.Handler.class);
    when(context.getDatabaseCluster()).thenReturn(cluster);
    when(cluster.getDistributedManager()).thenReturn(manager);
    when(manager.getExtContext(H2RecoveryCommand.Handler.class.getName())).thenReturn(handler);
    H2RecoveryCommand command = H2RecoveryCommand.probe("target-db", SHA);
    H2RecoveryCommand.Reply expected = H2RecoveryCommand.Reply.unsupported("target-db", SHA);
    when(handler.handle(command)).thenReturn(expected);
    assertSame(expected, command.execute(context));
    verify(handler).handle(command);
    verify(context, never()).execute(any(), any());
  }

  @Test public void restoreStillHonorsExistingSyncAllowedRoots() {
    H2RecoveryCommand.Operation operation = operation();
    System.setProperty(SyncFilePath.ALLOWED_ROOTS_PROPERTY, temporary.getRoot().toPath().resolve("allowed").toString());
    H2RecoveryCommand outbound = H2RecoveryCommand.restore(operation, SHA, temporary.getRoot().toPath().resolve("outside.dump").toString());
    try {
      outbound.execute(mock(StateCommandContext.class));
      fail("Expected disallowed path");
    } catch (IllegalArgumentException expected) { }
  }

  @Test public void commitEvidenceAndReplySurviveSerializationWithoutLosingRestoredGeneration() throws Exception {
    H2RecoveryCommand.Operation operation = operation();
    Path path = temporary.getRoot().toPath().resolve("target.intent");
    H2ActivationIntent.create(path, SHA);
    try (H2ActivationIntent intent = new H2ActivationIntent(path, SHA)) {
      intent.prepare(operation.epoch, operation.id, operation.owner(), operation.ownerDatabase,
          operation.membership(), SHA, operation.participants(), operation.databases);
      intent.commit(operation.epoch, operation.id, 4);
      H2RecoveryCommand decision = H2RecoveryCommand.apply("target-db", SHA, intent.snapshot());
      H2RecoveryCommand copied = net.sf.hajdbc.util.Objects.deserialize(net.sf.hajdbc.util.Objects.serialize(decision));
      copied.validate();
      assertEquals(4, copied.evidence.restoredGeneration);
      assertEquals(operation.membership(), copied.evidence.membership);
      H2RecoveryCommand.Reply reply = new H2RecoveryCommand.Reply(H2RecoveryCommand.Status.DECIDED,
          "target-db", SHA, "source-db", operation.ownerState, operation.ownerLock, intent.snapshot(), 4, 7, false,
          operation.stateView, operation.lockView);
      H2RecoveryCommand.Reply response = net.sf.hajdbc.util.Objects.deserialize(net.sf.hajdbc.util.Objects.serialize(reply));
      assertEquals(1, response.protocol);
      assertEquals(4, response.intent.restoredGeneration);
      assertEquals(H2ActivationIntent.Decision.COMMIT, response.intent.decision);
      assertEquals(operation.ownerState, response.localState);
      assertEquals(operation.databases, response.intent.databases);
      assertTrue(response.matchesViews(operation.ownerState, operation.stateView, operation.lockView));
    }
  }

  @Test public void unrelatedOwnerAndInvalidGenerationCannotConstructAnOperation() {
    H2RecoveryCommand.Operation operation = operation();
    for (long generation : new long[] { -1, Long.MAX_VALUE }) {
      try {
        new H2RecoveryCommand.Operation("target-db", SHA, "source-db", 7, "operation", generation,
            operation.ownerState, operation.ownerLock, operation.stateView, operation.lockView);
        fail();
      } catch (IllegalArgumentException expected) { }
    }
    try {
      new H2RecoveryCommand.Operation("target-db", SHA, "source-db", 7, "operation", 0,
          new AddressMember(UUID.randomUUID()), operation.ownerLock, operation.stateView, operation.lockView);
      fail();
    } catch (IllegalArgumentException expected) { }
  }

  @Test public void missingOrChangedViewsAndWrongResponderCannotPassPreflight() {
    H2RecoveryCommand.Operation operation = operation();
    H2RecoveryCommand.Reply old = new H2RecoveryCommand.Reply(H2RecoveryCommand.Status.SUPPORTED,
        "target-db", SHA, "source-db", operation.ownerState, operation.ownerLock, null, 0, 0, false);
    assertFalse(old.matchesViews(operation.ownerState, operation.stateView, operation.lockView));
    H2RecoveryCommand.Reply response = new H2RecoveryCommand.Reply(H2RecoveryCommand.Status.SUPPORTED,
        "target-db", SHA, "source-db", operation.ownerState, operation.ownerLock, null, 0, 0, false,
        operation.stateView, operation.lockView);
    assertTrue(response.matchesViews(operation.ownerState, operation.stateView, operation.lockView));
    assertFalse(response.matchesViews(new AddressMember(UUID.randomUUID()), operation.stateView, operation.lockView));
    MembershipSnapshot changed = new MembershipSnapshot(operation.ownerState, 2, operation.stateView.getMembers());
    assertFalse(response.matchesViews(operation.ownerState, changed, operation.lockView));
    assertFalse(response.matchesViews(operation.ownerState, operation.lockView, operation.stateView));
  }
}
