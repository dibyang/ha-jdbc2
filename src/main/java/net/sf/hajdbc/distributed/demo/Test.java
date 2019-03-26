package net.sf.hajdbc.distributed.demo;

import net.sf.hajdbc.distributed.jgroups.JGroupsCommandDispatcherFactory;

public class Test {
  public static void main(String[] args) throws Exception {
    JGroupsCommandDispatcherFactory factory = new JGroupsCommandDispatcherFactory();
    TestManager manager = new TestManager(factory);
    manager.start();
    for(int i=0;i<1000;i++) {
      System.out.println("i = " + i);
      manager.test();
      Thread.sleep(3000);
    }
    manager.stop();
  }
}
