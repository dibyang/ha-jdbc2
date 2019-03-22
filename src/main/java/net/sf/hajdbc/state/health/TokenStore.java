package net.sf.hajdbc.state.health;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class TokenStore {
  public static final String TOKEN_DAT = "token.dat";

  protected final Path path ;
  private volatile long lastModified = 0;
  private volatile long token = 0;

  public TokenStore(Path path) {
    this(path,false);
  }

  public TokenStore(Path path,boolean autoCreate) {
    this.path = path;
    checkPath(path, autoCreate);
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
        try {
          Files.write(path, String.valueOf(token).getBytes());
          lastModified = path.toFile().lastModified();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

}
