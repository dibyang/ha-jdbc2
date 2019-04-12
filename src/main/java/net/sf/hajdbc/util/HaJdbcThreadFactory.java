package net.sf.hajdbc.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class HaJdbcThreadFactory implements ThreadFactory {
  private final String name;
  private AtomicInteger atoInteger = new AtomicInteger(0);

  public HaJdbcThreadFactory(String name) {
    this.name = name;
  }

  public Thread newThread(Runnable r) {
    Thread t = new Thread(r);
    StringBuilder s = new StringBuilder();
    s.append(name)
        .append(" ")
        .append(atoInteger.getAndIncrement());
    t.setName(s.toString());
    return t;
  }

  public static HaJdbcThreadFactory c(String name){
    HaJdbcThreadFactory factory = new HaJdbcThreadFactory(name);
    return  factory;
  }
}
