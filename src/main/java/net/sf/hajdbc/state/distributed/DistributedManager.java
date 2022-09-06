package net.sf.hajdbc.state.distributed;

import java.util.List;
import java.util.Map;
import net.sf.hajdbc.Database;
import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.distributed.MembershipListener;

public interface DistributedManager<Z, D extends Database<Z>> {
  void addMembershipListener(MembershipListener listener);
  void removeMembershipListener(MembershipListener listener);
  Member getLocal();
  String getLocalIp();
  Member getCoordinator();
  List<Member> getMembers();
  Member getMember(String ip);
  <R> Map<Member, R> executeAll(Command<R, StateCommandContext<Z, D>> command,
      Member... excludedMembers);
  <R> R execute(Command<R, StateCommandContext<Z, D>> command, Member member);
  <C> C getExtContext(String key);
  <C> C removeExtContext(String key);
  <C> void setExtContext(String key, C context);
}
