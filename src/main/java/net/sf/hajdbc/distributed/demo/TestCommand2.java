package net.sf.hajdbc.distributed.demo;

import net.sf.hajdbc.distributed.Command;

public class TestCommand2 implements Command<Boolean,TestCommandContext> {


  @Override
  public Boolean execute(TestCommandContext context) {
    System.out.println("run "+ TestCommand2.class.getSimpleName());
    TestCommand3 cmd = new TestCommand3();
    Boolean execute = context.execute(cmd, context.getCoordinator());
    System.out.println(TestCommand2.class.getSimpleName()+" execute = " + execute);
    return true;
  }
}
