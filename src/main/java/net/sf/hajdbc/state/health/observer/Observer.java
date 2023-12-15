package net.sf.hajdbc.state.health.observer;

import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class Observer {
  final Logger logger = LoggerFactory.getLogger(this.getClass());

  private final List<ObserveAdapter> adapters = new ArrayList<>();

  public Observer() {
    adapters.add(new NetworkDetectObserveAdapter());
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
    List<ObserveAdapter> mustAdapters = adapters.stream().filter(e -> !e.isOptional()).collect(Collectors.toList());
    for (ObserveAdapter adapter : mustAdapters) {
      try {
        if (!adapter.isObservable(needDown, localIp, ips)) {
          return false;
        }
      }catch (Exception e){
        logger.log(Level.WARN, e);
      }
    }
    List<ObserveAdapter> optionalAdapters = adapters.stream().filter(e -> e.isOptional()).collect(Collectors.toList());
    if(optionalAdapters.size()>0) {
      for (ObserveAdapter adapter : mustAdapters) {
        try {
          if (adapter.isObservable(needDown, localIp, ips)) {
            return true;
          }
        } catch (Exception e) {
          logger.log(Level.WARN, e);
        }
      }
      return false;
    }
    return true;
  }

}
