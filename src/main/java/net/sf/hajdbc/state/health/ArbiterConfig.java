package net.sf.hajdbc.state.health;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.sf.hajdbc.util.LocalHost;
import net.sf.hajdbc.util.NetMasks;

public class ArbiterConfig {

  public static final String ARBITER_PATH = "arbiterpath";
  public static final String ARBITER_PATH_DEFAULT = "/datapool/.sconf/";
  public static final String IP = "ip";
  protected final Path path ;
  private volatile long lastModified = 0;
  private String arbiterPath;
  private final List<String> ips = new CopyOnWriteArrayList<>();
  private final Properties properties = new Properties();
  private volatile short prefixLen =16;
  private volatile String local ="";

  public ArbiterConfig() {
    this.path = Paths.get(System.getProperty("user.dir"), "conf","arbiter.conf");
  }

  public String getArbiterPath() {
    return arbiterPath;
  }

  public void setArbiterPath(String arbiterPath) {
    this.arbiterPath = NVLPATH(arbiterPath);
    save();
  }

  public String getLocal() {
    return local;
  }

  public void setLocal(String local) {
    this.local = local;
    prefixLen=LocalHost.getPrefixLength(local);
  }

  public List<String> getIps() {
    return new ArrayList<>(ips);
  }

  public void setIps(List<String> ips){
    this.ips.clear();
    if(ips!=null){
      this.ips.addAll(ips);
      checkLocalIp();
    }
    save();
  }

  private void reload() {
    if (path.toFile().lastModified() != lastModified) {
      lastModified = path.toFile().lastModified();
      synchronized (properties) {

        try (InputStream input = Files.newInputStream(path)){
          properties.clear();
          properties.load(input);
          setArbiterPath(properties.getProperty(ARBITER_PATH));
          ips.clear();
          String ip = properties.getProperty(IP);
          if(ip!=null){

            String[] ss = ip.split("\\s");
            for(String s : ss){
              if(s!=null){
                ips.add(s.trim());
              }
            }
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }
    boolean needSave = checkLocalIp();
    if(needSave){
      save();
    }
  }

  private boolean checkLocalIp() {
    boolean needSave = false;
    Set<String> allIp = LocalHost.getAllIp();
    Iterator<String> iterator = ips.iterator();
    while(iterator.hasNext()){
      String next = iterator.next();
      if(next==null) {
        iterator.remove();
        needSave = true;
      }else{
        String ip = next.trim();
        if(allIp.contains(ip)||!NetMasks.isSameSubnet(prefixLen,local, ip)){
          iterator.remove();
          needSave = true;
        }
      }
    }
    return needSave;
  }

  private String  NVLPATH(String path) {
    if(path==null||path.isEmpty()){
      return  ARBITER_PATH_DEFAULT;
    }
    return path;
  }

  public void save(){
    synchronized (properties){
      try(OutputStream output = Files.newOutputStream(path)){
        properties.clear();
        properties.setProperty(ARBITER_PATH,arbiterPath);
        StringBuilder ip = new StringBuilder();
        Iterator<String> iterator = ips.iterator();
        while(iterator.hasNext()){
          String next = iterator.next();
          if(next!=null) {
            ip.append(" ").append(next);
          }
        }
        properties.setProperty(IP, ip.toString());
        lastModified = path.toFile().lastModified();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

}
