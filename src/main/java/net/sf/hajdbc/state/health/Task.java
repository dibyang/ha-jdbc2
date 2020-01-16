package net.sf.hajdbc.state.health;

import java.util.concurrent.Callable;

/**
 * @author yangzj
 * @date 2019/12/31
 */
public interface Task<V> extends Callable<V> {
  V failed(Exception e);
  void success(V value);
}
