package net.sf.hajdbc.state.health.observer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

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
   * @param ip ip address
   * @return observable or not
   */
  public boolean isObservable(String ip){
    for (ObserveAdapter adapter: adapters){
      if(adapter.isObservable(ip)){
        return true;
      }
    }
    return false;
  }

}
