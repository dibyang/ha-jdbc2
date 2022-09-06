package net.sf.hajdbc.state.health;


import java.nio.file.Path;
import java.nio.file.Paths;

public enum PathHelper {
  helper;
  public String getRoot(){
    return System.getProperty("app.root",System.getProperty("user.dir"));
  }

  public Path get(String ... more){
    return Paths.get(getRoot(),more);
  }

}
