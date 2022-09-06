package net.sf.hajdbc.state.health;

import net.sf.hajdbc.util.LocalHost;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 仲裁者配置类
 */
public class ArbiterConfig {
  public static final String ARBITER_PATH_DEFAULT = "/.sconf/";
  protected final Path path;
  private String arbiterPath = ARBITER_PATH_DEFAULT;
  private final List<String> ips = new CopyOnWriteArrayList<>();
  private volatile short prefixLen = 16;
  private volatile String local = "";


  public ArbiterConfig() {
    this.path = PathHelper.helper.get("conf", "arbiter.conf");
  }

  public String getArbiterPath() {
    return MountPathHolder.H.getMountPath() + arbiterPath;
  }

  public String getLocal() {
    return local;
  }

  public void setLocal(String local) {
    this.local = local;
    prefixLen = LocalHost.getPrefixLength(local);
  }

  public List<String> getIps() {
    return new ArrayList<>(ips);
  }

}
