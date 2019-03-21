package net.sf.hajdbc.state.health;


import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.sf.hajdbc.state.health.observer.Observer;

public class Arbiter {
  private final LocalTokenStore local = new LocalTokenStore();
  private final TokenStore arbiter;
  private final Observer observer = new Observer();
  private final List<String> ips = new ArrayList<>();

  public Arbiter(String path) {
    arbiter = new TokenStore(Paths.get(path,TokenStore.TOKEN_DAT));
  }


  public LocalTokenStore getLocal() {
    return local;
  }

  public TokenStore getArbiter() {
    return arbiter;
  }

  public void update(long token){
    local.update(token);
    arbiter.update(token);
  }

  public boolean existsArbiter(){
    return arbiter.exists();
  }

  /**
   * Return observable or not
   * @return observable or not
   */
  public boolean isObservable(){
    if(ips.isEmpty()){
      return true;
    }else{
      for(String ip : ips){
        if(observer.isObservable(ip)){
          return true;
        }
      }
    }
    return false;
  }


}
