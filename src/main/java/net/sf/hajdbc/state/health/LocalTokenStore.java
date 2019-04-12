package net.sf.hajdbc.state.health;

import java.nio.file.Paths;

public class LocalTokenStore  extends TokenStore{



  public LocalTokenStore(String tokenName) {
    super(Paths.get(System.getProperty("user.dir"), tokenName),true);
  }
}
