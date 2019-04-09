package net.sf.hajdbc.state.health;


import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import net.sf.hajdbc.state.health.observer.Observer;

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
    checkMount();
  }


  private Path getArbiterPath() {
    return Paths.get(config.getArbiterPath(),tokenName);
  }


  public LocalTokenStore getLocal() {
    return local;
  }

  public TokenStore getArbiter() {
    checkPathChange();
    return arbiter;
  }

  public void setLocal(String local) {
    config.setLocal(local);
  }

  private void checkPathChange() {
    Path path = getArbiterPath();
    if(!arbiter.getPath().equals(path)){
      arbiter.setPath(path);
      checkMount();
    }
  }

  private void checkMount() {
    Path path = Paths.get("/proc/mounts");
    if(Files.exists(path)){
      try {
        List<String> lines = Files.readAllLines(path, Charset.defaultCharset());
        if(lines!=null){
          for(String line : lines){
            if(line!=null&&line.startsWith("none")&&line.contains(" LeoFS ")){
              int bindex = 5;
              int eindex = line.indexOf(" LeoFS ",bindex);
              if(eindex>bindex){
                String mount = line.substring(5,eindex).trim()+"/";
                String arbiterPath = config.getArbiterPath();
                if(arbiterPath.startsWith(mount)){
                  Path parent = Paths.get(arbiterPath);
                  if(!Files.exists(parent)){
                    Files.createDirectories(parent);
                    break;
                  }
                }
              }

            }
          }
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
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
