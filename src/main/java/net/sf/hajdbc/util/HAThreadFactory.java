package net.sf.hajdbc.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class HAThreadFactory implements ThreadFactory {
  private final ThreadGroup group;
  private final AtomicInteger threadNumber = new AtomicInteger(1);
  private final String namePrefix;

  private HAThreadFactory(String name) {
      SecurityManager s = System.getSecurityManager();
      group = (s != null) ? s.getThreadGroup() :
                            Thread.currentThread().getThreadGroup();
      namePrefix = "n2-" +name +
                   "-thread-";
  }

  public Thread newThread(Runnable r) {
      Thread t = new Thread(group, r,
                            namePrefix + threadNumber.getAndIncrement(),
                            0);
      if (t.isDaemon())
          t.setDaemon(false);
      if (t.getPriority() != Thread.NORM_PRIORITY)
          t.setPriority(Thread.NORM_PRIORITY);
      return t;
  }
  
  public static HAThreadFactory c(String name){
    return new HAThreadFactory(name);
  }
}
