package net.sf.hajdbc.state.sync;

import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.state.distributed.DistributedStateManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 验证单次入口不会沿用通用命令的空响应重试。 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class SyncMgrSingleAttemptTest {
  @Test public void nullResponseIsDispatchedOnlyOnce() {
    DistributedStateManager state = mock(DistributedStateManager.class);
    Member target = mock(Member.class);
    SyncCommand command = mock(SyncCommand.class);
    assertNull(new SyncMgrImpl(state).executeOnce(target, command));
    verify(state, times(1)).execute(command, target);
  }

  @Test public void genericExecutionRetainsExistingRetries() {
    DistributedStateManager state = mock(DistributedStateManager.class);
    Member target = mock(Member.class);
    SyncCommand command = mock(SyncCommand.class);
    assertNull(new SyncMgrImpl(state).execute(target, command));
    verify(state, times(3)).execute(command, target);
  }
}
