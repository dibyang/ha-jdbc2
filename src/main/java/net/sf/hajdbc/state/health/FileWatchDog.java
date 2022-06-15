package net.sf.hajdbc.state.health;

import org.jgroups.auth.sasl.FileObserver;

import java.io.File;

/**
 * @ClassName FileWatchDog
 * @Description 文件看门口狗
 * @Author zhxq
 * @Date 2020/4/18 9:44
 */
public class FileWatchDog {
  /**
   * 要看守的文件
   */
  private final File file;

  /**
   * 文件最后的修改时间
   */
  private long modified = 0;

  /**
   * 通知文件的观察者
   */
  private final FileObserver observer;

  public FileWatchDog(File file, FileObserver observer) {
    this.file = file;
    this.observer = observer;
  }

  public void watch() {

    long modified = 0;
    if(this.file.exists()){
      modified = this.file.lastModified();
    }
    if (this.modified != modified) {
      this.modified = modified;
      this.observer.fileChanged(this.file);
    }

  }

}
