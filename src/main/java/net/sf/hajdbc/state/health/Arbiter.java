package net.sf.hajdbc.state.health;


import java.nio.file.Path;
import java.nio.file.Paths;
import net.sf.hajdbc.state.health.observer.Observer;

public class Arbiter {
  private final LocalTokenStore local = new LocalTokenStore();
  private final TokenStore arbiter;
  private final Observer observer = new Observer();
  private final ArbiterConfig config = new ArbiterConfig();

  public Arbiter() {
    arbiter = new TokenStore(getArbiterPath());
  }

  private Path getArbiterPath() {
    return Paths.get(config.getArbiterPath(), TokenStore.TOKEN_DAT);
  }


  public LocalTokenStore getLocal() {
    return local;
  }

  public TokenStore getArbiter() {
    checkPathChange();
    return arbiter;
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
      for(String ip : config.getIps()){
        if(observer.isObservable(ip)){
          return true;
        }
      }
    }
    return false;
  }


}
