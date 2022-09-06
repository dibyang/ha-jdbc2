package net.sf.hajdbc.state.health;

import net.sf.hajdbc.util.HAThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * @author yangzj
 * @date 2019/12/30
 */
public class TimeoutUtil {
  static final Logger logger = LoggerFactory.getLogger(TimeoutUtil.class);

  public static final int DEFAULT_TIMEOUT = 1000;
  private final int timeout;

  private final ExecutorService exec;

  public TimeoutUtil(String name){
    this(name,DEFAULT_TIMEOUT,2);
  }

  public TimeoutUtil(int timeout){
    this("TimeoutUtil",timeout,2);
  }

  public TimeoutUtil(String name, int timeout){
    this(name,timeout,2);
  }

  public TimeoutUtil(String name, int timeout,int nThreads) {
    this.timeout = timeout;
    exec= Executors.newFixedThreadPool(nThreads, HAThreadFactory.c(name));
  }

  public int getTimeout() {
    return timeout;
  }


  public void call(final Runnable runnable){
    call(runnable, timeout,TimeUnit.MILLISECONDS);
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
    return call(callable,defValue,timeout,TimeUnit.MILLISECONDS);
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
    return call(task,timeout,TimeUnit.MILLISECONDS);
  }

  public <V> V call(final Task<V> task, long timeout, TimeUnit unit){
    Future<V> future = exec.submit(task);
    try{
      V value = future.get(timeout, unit);
      task.success(value);
      return value;
    } catch (Exception e) {
      return task.failed(e);
    }
  }
}
