package net.sf.hajdbc.dialect.h2;

import java.io.File;
import java.sql.SQLException;
import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.exception.CommandNotFoundException;
import net.sf.hajdbc.state.sync.SyncMgr;
import net.sf.hajdbc.sync.SynchronizationContext;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 验证恢复结果必须明确成功，任何未知结果都不能再次发送清库请求。 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class H2RestoreResultTest {
  private final SyncMgr sync = mock(SyncMgr.class);
  private final Database database = mock(Database.class);
  private final DatabaseCluster cluster = mock(DatabaseCluster.class);
  private final SynchronizationContext context = mock(SynchronizationContext.class);
  private final Member member = mock(Member.class);
  private final File script = new File("restore-test.dump");
  private final File zip = new File("restore-test.dump.zip");

  @Before public void configure() {
    when(context.getDatabaseCluster()).thenReturn(cluster);
    when(cluster.getSyncMgr()).thenReturn(sync);
    when(sync.getMember(database)).thenReturn(member);
    when(sync.upload(member, zip)).thenReturn(true);
  }

  private void restore() throws Exception {
    new H2Dialect().restore(context, database, null, script, false);
  }

  @Test public void successRequiresTrueAndSendsOnce() throws Exception {
    when(sync.executeOnce(eq(member), any(H2RunScriptCommand2.class))).thenReturn(true);
    restore();
    verify(sync).executeOnce(eq(member), any(H2RunScriptCommand2.class));
    verify(sync, never()).execute(any(), any());
  }

  @Test public void falseFailsWithoutRetry() throws Exception { rejectedResult(false, "failed on target"); }
  @Test public void nullFailsWithoutRetry() throws Exception { rejectedResult(null, "unknown"); }

  private void rejectedResult(Boolean result, String message) throws Exception {
    when(sync.executeOnce(eq(member), any(H2RunScriptCommand2.class))).thenReturn(result);
    try { restore(); fail("Expected restore failure"); }
    catch (SQLException e) { assertTrue(e.getMessage().contains(message)); }
    verify(sync).executeOnce(eq(member), any(H2RunScriptCommand2.class));
    verify(sync, never()).execute(any(), any());
  }

  @Test public void missingCommandDoesNotFallBackToAnotherDestructiveCommand() throws Exception {
    CommandNotFoundException failure = new CommandNotFoundException("restore");
    when(sync.executeOnce(eq(member), any(H2RunScriptCommand2.class))).thenThrow(failure);
    try { restore(); fail("Expected unsupported command"); }
    catch (CommandNotFoundException e) { assertSame(failure, e); }
    verify(sync).executeOnce(eq(member), any(H2RunScriptCommand2.class));
    verify(sync, never()).upload(member, script);
    verify(sync, never()).execute(any(), any());
  }

  @Test public void uploadFailureDoesNotExecute() throws Exception {
    when(sync.upload(member, zip)).thenReturn(false);
    rejectBeforeExecution();
  }

  @Test public void absentMemberDoesNotExecute() throws Exception {
    when(sync.getMember(database)).thenReturn(null);
    rejectBeforeExecution();
    verify(sync, never()).upload(any(), any(File.class));
  }

  @Test public void localTargetCannotSilentlySucceed() throws Exception {
    when(database.isLocal()).thenReturn(true);
    rejectBeforeExecution();
    verify(sync, never()).upload(any(), any(File.class));
  }

  private void rejectBeforeExecution() throws Exception {
    try { restore(); fail("Expected restore failure"); }
    catch (SQLException expected) { }
    verify(sync, never()).executeOnce(any(), any());
    verify(sync, never()).execute(any(), any());
  }
}
