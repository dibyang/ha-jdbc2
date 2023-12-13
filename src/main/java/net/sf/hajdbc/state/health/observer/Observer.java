package net.sf.hajdbc.state.health.observer;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class Observer {
  private final List<ObserveAdapter> adapters = new ArrayList<>();

  public Observer() {
    ServiceLoader<ObserveAdapter> loader = ServiceLoader.load(ObserveAdapter.class);
    Iterator<ObserveAdapter> iterator = loader.iterator();
    while(iterator.hasNext()){
      adapters.add(iterator.next());
    }
    Collections.sort(adapters, new Comparator<ObserveAdapter>() {
      @Override
      public int compare(ObserveAdapter o1, ObserveAdapter o2) {
        return Integer.compare(o2.getWeight(),o1.getWeight());
      }
    });
  }

  /**
   * Observe adapters
   * @return Observe adapters
   */
  public List<ObserveAdapter> getAdapters() {
    return Collections.unmodifiableList(adapters);
  }

  /**
   * Return observable or not
   * @param needDown 是否是下线检测，false表示上线检测
   * @param localIp local ip
   * @param ips ip list
   * @return observable or not
   */
  public boolean isObservable(boolean needDown, String localIp, List<String> ips){
    for (ObserveAdapter adapter : adapters) {
      if (!adapter.isObservable(needDown, localIp, ips)) {
        return false;
      }
    }
    return true;
  }

}
