package net.sf.hajdbc.state.health;

import net.sf.hajdbc.util.HAThreadFactory;
import net.sf.hajdbc.util.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * @author yangzj
 * @date 2019/12/30
 */
public class TimeoutUtil {
  static final Logger logger = LoggerFactory.getLogger(TimeoutUtil.class);

  public static final int DEFAULT_TIMEOUT = 200;


  private final ExecutorService exec;


  public TimeoutUtil(String name){
    this(name,2);
  }

  public TimeoutUtil(String name, int nThreads) {
    exec= Executors.newFixedThreadPool(nThreads, HAThreadFactory.c(name));
  }



  public void call(final Runnable runnable){
    call(runnable, DEFAULT_TIMEOUT, TimeUnit.MILLISECONDS);
  }

  public void call(final Runnable runnable,long timeout, TimeUnit unit){
    Callable callable = new Callable() {
      @Override
      public Object call() throws Exception {
        runnable.run();
        return null;
      }
    };
    call(callable,null,timeout,unit);
  }

  public <V> V call(Callable<V> callable){
    return call(callable,null);
  }

  public <V> V call(Callable<V> callable, V defValue){
    return call(callable, defValue, DEFAULT_TIMEOUT, TimeUnit.MILLISECONDS);
  }

  public <V> V call(final Callable<V> callable, final V defValue, long timeout, TimeUnit unit){
    Task<V> task = new Task<V>() {
      @Override
      public V failed(Exception e) {
        return defValue;
      }

      @Override
      public void success(V value) {

      }

      @Override
      public V call() throws Exception {
        return callable.call();
      }
    };
    return call(task,timeout,TimeUnit.MILLISECONDS);
  }

  public <V> V call(final Task<V> task){
    return call(task, DEFAULT_TIMEOUT, TimeUnit.MILLISECONDS);
  }

  public <V> V call(final Task<V> task, long timeout, TimeUnit unit){
    Future<V> future = exec.submit(task);
    try{
      V value = future.get(timeout, unit);
      task.success(value);
      return value;
    } catch (Exception e) {
      future.cancel(true);
      return task.failed(e);
    }
  }

  public static void main(String[] args) {
    TimeoutUtil util = new TimeoutUtil("test");

    util.call(()->{
      StopWatch stopWatch = StopWatch.createStarted();
      try {
        Thread.sleep(5000);
      } catch (InterruptedException e) {
        //e.printStackTrace();
      }
      System.out.println("cost time:"+stopWatch.toString());
    });
    util.call(()->{
      StopWatch stopWatch = StopWatch.createStarted();
      try {
        Thread.sleep(5000);
      } catch (InterruptedException e) {
        //e.printStackTrace();
      }
      System.out.println("cost time:"+stopWatch.toString());
    });
    util.call(()->{
      StopWatch stopWatch = StopWatch.createStarted();
      try {
        Thread.sleep(5000);
      } catch (InterruptedException e) {
        //e.printStackTrace();
      }
      System.out.println("cost time:"+stopWatch.toString());
    });
    util.call(()->{
      StopWatch stopWatch = StopWatch.createStarted();
      try {
        Thread.sleep(5000);
      } catch (InterruptedException e) {
        //e.printStackTrace();
      }
      System.out.println("cost time:"+stopWatch.toString());
    });

  }
}
