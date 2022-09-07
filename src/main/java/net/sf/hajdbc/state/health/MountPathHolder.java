package net.sf.hajdbc.state.health;

import org.jgroups.auth.sasl.FileObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 挂载点的持有者
 * @author zhxq
 */
public enum MountPathHolder implements FileObserver {
  H;

  static final Logger LOG = LoggerFactory.getLogger(MountPathHolder.class);
  private String mountPath = "/datapool";

  public String getMountPath() {
    return mountPath;
  }

  @Override
  public void fileChanged(File file) {
    Path mounts = file.toPath();
    if (Files.exists(mounts)) {
      try {
        List<String> lines = Files.readAllLines(mounts, Charset.defaultCharset());
        if (lines != null) {
          for (String line : lines) {
            if (line != null && line.startsWith("none") && line.contains(" LeoFS ")) {
              int bindex = 5;
              int eindex = line.indexOf(" LeoFS ", bindex);
              if (eindex > bindex) {
                String mount = line.substring(5, eindex).trim() + "/";
                if (mount != null && !mount.isEmpty()) {
                  this.mountPath = mount;
                  break;
                }
              }
            }
          }
        }
      } catch (IOException e) {
        LOG.error("read /proc/mounts file failed", e);
      }
    }
  }
}
