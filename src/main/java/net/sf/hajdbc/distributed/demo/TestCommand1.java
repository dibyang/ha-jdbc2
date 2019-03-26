package net.sf.hajdbc.distributed.demo;

import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.distributed.Member;

public class TestCommand1 implements Command<Boolean,TestCommandContext> {
  private Member src;

  public Member getSrc() {
    return src;
  }

  public void setSrc(Member src) {
    this.src = src;
  }

  @Override
  public Boolean execute(TestCommandContext context) {
    System.out.println("run "+TestCommand1.class.getSimpleName());
    if(src!=null){
      TestCommand2 cmd = new TestCommand2();
      Boolean execute = context.execute(cmd, src);
      System.out.println(TestCommand1.class.getSimpleName()+" execute = " + execute);
    }
    return true;
  }
}
