package net.sf.hajdbc.state.health;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class TokenStore {
  public static final String TOKEN_DAT = "token.dat";
  public static final String ONLYHOST_TRUE = "1";
  public static final String ONLYHOST_FALSE = "0";
  public static final String NEWLINE = "\n";

  protected Path path ;
  private boolean autoCreate;
  private volatile long lastModified = 0;
  private volatile boolean onlyHost = false;
  private volatile long token = 0;

  public TokenStore(Path path) {
    this(path,false);
  }

  public TokenStore(Path path,boolean autoCreate) {
    this.autoCreate = autoCreate;
    this.setPath(path);
  }

  public void setPath(Path path) {
    this.path = path;
    checkPath(path, autoCreate);
    lastModified = 0;
  }

  public Path getPath() {
    return path;
  }

  public void setAutoCreate(boolean autoCreate) {
    this.autoCreate = autoCreate;
  }

  public boolean isAutoCreate() {
    return autoCreate;
  }

  private void checkPath(Path path, boolean autoCreate) {
    Path parent = path.getParent();
    if(autoCreate){
      if(!Files.exists(parent)){
        parent.toFile().mkdirs();
      }
    }
    if(Files.exists(parent)){
      if(!exists()){
        try {
          path.toFile().createNewFile();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  public boolean exists() {
    return Files.exists(path);
  }

  public boolean isOnlyHost() {
    reload();
    return onlyHost;
  }

  public void setOnlyHost(boolean onlyHost) {
    reload();
    if(this.onlyHost!=onlyHost) {
      this.onlyHost = onlyHost;
      save();
    }
  }

  public void update(long token){
    reload();
    if(this.token != token) {
      this.token = token;
      save();
    }
  }

  public long getToken() {
    reload();
    return token;
  }

  private void reload() {
    if(exists()) {
      if (path.toFile().lastModified() != lastModified) {
        lastModified = path.toFile().lastModified();
        synchronized (this) {

          try {
            List<String> lines = Files.readAllLines(path, Charset.defaultCharset());
            if (lines.size() > 0) {
              token = Long.parseLong(lines.get(0));
              if(lines.size() > 1){
                onlyHost = ONLYHOST_TRUE.equals(lines.get(1));
              }
            }

          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      }
    }
  }

  public void save(){
    synchronized (this){
      if(exists()) {

        StringBuilder builder = new StringBuilder();

        builder.append(token).append(NEWLINE);
        builder.append(onlyHost?ONLYHOST_TRUE:ONLYHOST_FALSE).append(NEWLINE);

        try{
          Files.write(path,builder.toString().getBytes());
          lastModified = path.toFile().lastModified();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

}
