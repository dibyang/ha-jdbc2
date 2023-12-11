package net.sf.hajdbc.state.health;


import net.sf.hajdbc.state.health.observer.Observer;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Arbiter {
  private final LocalTokenStore local;
  private final TokenStore arbiter;
  private final Observer observer = new Observer();
  private final ArbiterConfig config = new ArbiterConfig();
  //private final String clusterId;
  private final String tokenName;


  public Arbiter(String clusterId) {
    //this.clusterId = clusterId;
    tokenName = clusterId+".token";
    local = new LocalTokenStore(tokenName);
    arbiter = new TokenStore(getArbiterPath());
  }


  private Path getArbiterPath() {
    return Paths.get(config.getArbiterPath(),tokenName);
  }


  public LocalTokenStore getLocalTokenStore() {
    return local;
  }

  public TokenStore getArbiter() {
    checkPathChange();
    return arbiter;
  }

  public void setLocalIp(String local) {
    config.setLocalIp(local);
  }

  private void checkPathChange() {
    Path path = getArbiterPath();
    if(!arbiter.getPath().equals(path)){
      arbiter.setPath(path);
    }
  }

  public void update(long token){
    local.update(token);
    checkPathChange();
    arbiter.update(token);
  }

  public boolean existsArbiter(){
    checkPathChange();
    return arbiter.exists();
  }

  /**
   * Return observable or not
   * @return observable or not
   */
  public boolean isObservable(){
    if(config.getIps().isEmpty()){
      return true;
    }else{
      if(observer.isObservable(config.getLocalIp(), config.getIps())){
        return true;
      }
    }
    return false;
  }


}
