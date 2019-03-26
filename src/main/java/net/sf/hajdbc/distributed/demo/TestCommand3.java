package net.sf.hajdbc.distributed.demo;

import net.sf.hajdbc.distributed.Command;

public class TestCommand3 implements Command<Boolean,TestCommandContext> {


  @Override
  public Boolean execute(TestCommandContext context) {
    System.out.println("run "+ TestCommand3.class.getSimpleName());

    return true;
  }
}
