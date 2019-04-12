package net.sf.hajdbc.state.health;

import java.nio.file.Paths;

public class LocalTokenStore  extends TokenStore{



  public LocalTokenStore(String tokenName) {
    super(PathHelper.helper.get(tokenName),true);
  }
}
