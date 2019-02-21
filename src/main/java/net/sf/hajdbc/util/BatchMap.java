package net.sf.hajdbc.util;


import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * HashMap batch support.
 * @author dib
 * @param <K>
 * @param <V>
 */
public class BatchMap<K,V> {
  final transient ReentrantLock lock = new ReentrantLock();
  private final Map<K,V> data = new HashMap<>();

  public void commit(Map<K,V> map){
    lock.lock();
    try {
      data.clear();
      data.putAll(map);
    }finally {
      lock.unlock();
    }
  }

  public Map<K,V> getData(){
    Map<K,V> map = new HashMap<>();
    lock.lock();
    try {
      map.putAll(data);
    }finally {
      lock.unlock();
    }
    return map;
  }


}

