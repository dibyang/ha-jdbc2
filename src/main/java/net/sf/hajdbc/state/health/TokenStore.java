package net.sf.hajdbc.state.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class TokenStore {
  static final Logger LOG = LoggerFactory.getLogger(TokenStore.class);
  public static final String ONLYHOST_TRUE = "1";
  public static final String ONLYHOST_FALSE = "0";
  public static final String NEWLINE = "\n";
  static final TimeoutUtil timeoutUtil = new TimeoutUtil("TokenStore");

  protected Path path ;
  private boolean local;
  private volatile long lastModified = 0;
  private volatile boolean onlyHost = false;
  private volatile long token = 0;

  public TokenStore(Path path) {
    this(path,false);
  }

  public TokenStore(Path path,boolean local) {
    this.local = local;
    this.setPath(path);
  }

  public void setPath(Path path) {
    this.path = path;
    checkPath(path, local);
    lastModified = 0;
  }

  public Path getPath() {
    return path;
  }

  public void setLocal(boolean local) {
    this.local = local;
  }

  public boolean isLocal() {
    return local;
  }

  private void checkMount() {
    String arbiterPath = path.toString();
    if(arbiterPath.startsWith(MountPathHolder.H.getMountPath())){
      Path parent = Paths.get(arbiterPath).getParent();
      if(!Files.exists(parent)){
        try {
          Files.createDirectories(parent);
        } catch (IOException e) {
          LOG.error("arbiter directory create failed:", e);
        }
      }
    }
  }
  private void checkPath(final Path path, final boolean local) {
    timeoutUtil.call(new Runnable() {
      @Override
      public void run() {
        Path parent = path.getParent();
        if(!local){
          checkMount();
        }else{
          if(!Files.exists(parent)){
            parent.toFile().mkdirs();
          }
        }

        if(Files.exists(parent)){
          if(!exists()){
            try {
              path.toFile().createNewFile();
            } catch (IOException e) {
              LOG.warn(null,e);
            }
          }
        }
      }
    });
  }

  public boolean exists() {
    Boolean exists = timeoutUtil.call(new Task<Boolean>() {
      @Override
      public Boolean failed(Exception e) {
        if(e instanceof TimeoutException){
          token = 0;
        }
        return false;
      }

      @Override
      public void success(Boolean value) {

      }

      @Override
      public Boolean call() throws Exception {
        return Files.exists(path);
      }
    }, false);
    return exists;
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
      timeoutUtil.call(new Task() {

        @Override
        public Object failed(Exception e) {
          if(e instanceof TimeoutException){
            token = 0;
          }
          return null;
        }

        @Override
        public void success(Object value) {

        }

        @Override
        public Object call() {
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
                LOG.warn(null,e);
              }
            }
          }
          return null;
        }
      });

    }
  }

  public void save(){
    synchronized (this){
      if(exists()) {
        timeoutUtil.call(new Runnable() {
          @Override
          public void run() {
            StringBuilder builder = new StringBuilder();

            builder.append(token).append(NEWLINE);
            builder.append(onlyHost?ONLYHOST_TRUE:ONLYHOST_FALSE).append(NEWLINE);

            try{
              Files.write(path,builder.toString().getBytes());
              lastModified = path.toFile().lastModified();
            } catch (IOException e) {
              LOG.warn(null,e);
            }
          }
        });
      }
    }
  }

}
