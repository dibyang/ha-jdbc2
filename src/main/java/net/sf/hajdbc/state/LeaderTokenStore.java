package net.sf.hajdbc.state;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Leader toeken store
 * @author dib
 */
public class LeaderTokenStore {
  public static final String TVER_KEY = "tver";
  public static final String LEADER_KEY = "leader";
  protected final Path path ;
  final Properties properties = new Properties();
  private volatile long lastModified = 0;
  private final LeaderToken token = new LeaderToken();

  public LeaderTokenStore(Path path) {
    this.path = path;
    if(!Files.exists(path)){
      Path parent = path.getParent();
      if(!Files.exists(parent)){
        parent.toFile().mkdirs();
      }
      try {
        path.toFile().createNewFile();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  public void update(LeaderToken token){
    this.token.update(token);
  }

  public LeaderToken getToken() {
    reload();
    return token.copy();
  }

  private void reload() {
    if(path.toFile().lastModified()!=lastModified) {
      lastModified = path.toFile().lastModified();
      synchronized (properties){
        try(InputStream input = Files.newInputStream(path)) {
          properties.load(input);
          String leader = properties.getProperty(LEADER_KEY,"");
          if(!properties.isEmpty()){
            String tver = properties.getProperty(TVER_KEY,"");
            token.setTver(Long.parseLong(tver.trim()));
            token.setLeader(leader);
          }else{
            token.setLeader(null);
            token.setTver(0);
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }
  }

  public void save(){
    synchronized (properties){
      try( OutputStream output = Files.newOutputStream(path) ){
        properties.clear();
        properties.put(TVER_KEY,String.valueOf(token.getTver()));
        properties.put(LEADER_KEY,token.getLeader());
        properties.store(output,null);
        lastModified = path.toFile().lastModified();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }



}
