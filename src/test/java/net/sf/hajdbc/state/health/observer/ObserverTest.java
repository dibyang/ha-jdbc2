package net.sf.hajdbc.state.health.observer;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class ObserverTest {
  Observer mgr = new Observer();

  @Test
  public void getAdapters() {
    List<ObserveAdapter> adapters = mgr.getAdapters();
    assertEquals("not ping","ping",adapters.get(0).getName());
    assertEquals("not test2","test2",adapters.get(1).getName());
    assertEquals("not test1","test1",adapters.get(2).getName());
  }



}