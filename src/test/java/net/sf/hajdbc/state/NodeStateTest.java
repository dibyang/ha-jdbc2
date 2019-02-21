package net.sf.hajdbc.state;

import org.junit.Test;


import java.util.Optional;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

public class NodeStateTest {

  @Test
  public void of() {

    NodeState ns = NodeState.of(null);
    assertNull(ns);
    ns = NodeState.of("");
    assertNull(ns);
    NodeState old = new NodeState();
    old.setIp("10.1.0.100");
    old.setTver(126);
    ns = NodeState.of(old.toString());
    assertNotNull(ns);
    assertEquals(old.getIp(),ns.getIp());
    assertEquals(old.getTver(),ns.getTver());
  }
}