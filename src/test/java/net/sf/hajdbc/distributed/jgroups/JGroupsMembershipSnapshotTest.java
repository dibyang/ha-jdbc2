package net.sf.hajdbc.distributed.jgroups;

import java.util.Arrays;
import net.sf.hajdbc.distributed.MembershipSnapshot;
import org.jgroups.Address;
import org.jgroups.JChannel;
import org.jgroups.View;
import org.jgroups.protocols.CENTRAL_LOCK;
import org.jgroups.protocols.Locking;
import org.jgroups.protocols.TCP_NIO2;
import org.jgroups.stack.ProtocolStack;
import org.jgroups.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 恢复确认使用实际 JGroups 视图身份，不能依赖返回数量或显示名称。 */
public class JGroupsMembershipSnapshotTest {
  private JChannel channel() {
    JChannel channel = mock(JChannel.class);
    ProtocolStack stack = mock(ProtocolStack.class);
    when(channel.getProtocolStack()).thenReturn(stack);
    when(stack.getTransport()).thenReturn(mock(TCP_NIO2.class));
    when(stack.findProtocol(Locking.class)).thenReturn(new CENTRAL_LOCK());
    when(channel.isConnected()).thenReturn(true);
    return channel;
  }

  @Test public void readsOneViewAndDefensivelyRetainsThatExactMemberSet() throws Exception {
    JChannel channel = channel();
    Address a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    View first = new View(a, 1, Arrays.asList(a, b));
    View second = new View(a, 2, Arrays.asList(a, c));
    JGroupsCommandDispatcher<Object> dispatcher = new JGroupsCommandDispatcher<>("test.state", channel, 1000, null, null, null);
    // 构造分发器也会读取视图；此处只计量恢复快照自身，且不让构造消耗测试视图。
    reset(channel);
    when(channel.isConnected()).thenReturn(true);
    when(channel.getView()).thenReturn(first, second);
    MembershipSnapshot snapshot = dispatcher.membershipSnapshot();
    verify(channel, times(1)).getView();
    assertEquals(2, snapshot.getMembers().size());
    assertTrue(snapshot.getMembers().contains(new AddressMember(b)));
    try { snapshot.getMembers().clear(); fail(); } catch (UnsupportedOperationException expected) { }
    MembershipSnapshot changed = dispatcher.membershipSnapshot();
    assertNotEquals(snapshot, changed);
    assertTrue(snapshot.getMembers().contains(new AddressMember(b)));
    assertTrue(changed.getMembers().contains(new AddressMember(c)));
    MembershipSnapshot copied = net.sf.hajdbc.util.Objects.deserialize(net.sf.hajdbc.util.Objects.serialize(snapshot));
    assertEquals(snapshot, copied);
  }

  @Test public void sameMembersInNewViewAreNotOldRestoreAuthority() throws Exception {
    Address a = UUID.randomUUID(), b = UUID.randomUUID();
    MembershipSnapshot first = new MembershipSnapshot(new AddressMember(a), 1,
        Arrays.asList(new AddressMember(a), new AddressMember(b)));
    MembershipSnapshot changed = new MembershipSnapshot(new AddressMember(a), 2,
        Arrays.asList(new AddressMember(a), new AddressMember(b)));
    assertEquals(first.getMembers(), changed.getMembers());
    assertNotEquals(first.getViewId(), changed.getViewId());
  }

  @Test public void memberIdentitySurvivesRenameButNotSameNameRestart() {
    Address a = UUID.randomUUID(), b = UUID.randomUUID();
    try {
      UUID.add(a, "display-name");
      String original = MembershipSnapshot.memberId(new AddressMember(a));
      UUID.add(a, "renamed");
      UUID.add(b, "renamed");
      assertEquals(original, MembershipSnapshot.memberId(new AddressMember(a)));
      assertNotEquals(original, MembershipSnapshot.memberId(new AddressMember(b)));
    } finally { UUID.remove(a); UUID.remove(b); }
  }

  @Test public void disconnectedOrMissingViewCannotAuthorizeRestore() throws Exception {
    JChannel channel = channel();
    JGroupsCommandDispatcher<Object> dispatcher = new JGroupsCommandDispatcher<>("test.state", channel, 1000, null, null, null);
    try { dispatcher.membershipSnapshot(); fail(); } catch (IllegalStateException expected) { }
    when(channel.isConnected()).thenReturn(false);
    try { dispatcher.membershipSnapshot(); fail(); } catch (IllegalStateException expected) { }
  }
}
