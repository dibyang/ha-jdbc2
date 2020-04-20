package net.sf.hajdbc.state.health;

public class LocalTokenStore  extends TokenStore{



  public LocalTokenStore(String tokenName) {
    super(PathHelper.helper.get(tokenName),true);
  }
}
