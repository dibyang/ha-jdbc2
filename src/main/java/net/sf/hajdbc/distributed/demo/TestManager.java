package net.sf.hajdbc.distributed.demo;

import net.sf.hajdbc.Lifecycle;
import net.sf.hajdbc.distributed.*;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

public class TestManager implements Lifecycle,TestCommandContext, Stateful, MembershipListener {
  final CommandDispatcher<TestCommandContext> dispatcher;

  public TestManager(CommandDispatcherFactory dispatcherFactory) throws Exception {
    this.dispatcher = dispatcherFactory.createCommandDispatcher("test",this,this,this);
  }

  @Override
  public void start() throws Exception {
    dispatcher.start();
  }

  @Override
  public void stop() {
    dispatcher.stop();
  }



  public void test(){
    if(!this.getLocal().equals(getCoordinator())) {
      TestCommand1 cmd = new TestCommand1();
      cmd.setSrc(getLocal());
      Boolean execute = execute(cmd, getCoordinator());
      System.out.println("execute = " + execute);
    }
  }

  @Override
  public Member getLocal() {
    return dispatcher.getLocal();
  }

  @Override
  public Member getCoordinator() {
    return dispatcher.getCoordinator();
  }

  @Override
  public <R> R execute(Command<R, TestCommandContext> cmd, Member member) {
    return dispatcher.execute(cmd,member);
  }

  @Override
  public void added(Member member) {

  }

  @Override
  public void removed(Member member) {

  }

  @Override
  public void readState(ObjectInput input) throws IOException, ClassNotFoundException {

  }

  @Override
  public void writeState(ObjectOutput output) throws IOException {

  }
}
