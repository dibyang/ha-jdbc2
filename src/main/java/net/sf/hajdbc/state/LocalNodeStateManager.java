package net.sf.hajdbc.state;

import java.nio.file.Paths;

public class LocalNodeStateManager extends NodeStateManager {

  public LocalNodeStateManager() {
    super(Paths.get(System.getProperty("user.dir"),"data/n2.ns"));
  }
}
