package net.sf.hajdbc.distributed.demo;


import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.distributed.Member;

public interface TestCommandContext {
  Member getLocal();
  Member getCoordinator();
 <R> R execute(Command<R,TestCommandContext> cmd, Member member);
}
