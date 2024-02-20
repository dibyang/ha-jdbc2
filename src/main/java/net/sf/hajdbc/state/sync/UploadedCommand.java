package net.sf.hajdbc.state.sync;

import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.state.distributed.StateCommandContext;
import net.sf.hajdbc.util.MD5;
import net.sf.hajdbc.util.StopWatch;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

public class UploadedCommand implements SyncCommand<Boolean> {
  static final Logger logger = LoggerFactory.getLogger(UploadedCommand.class);
  public static final String TMP_FILE_SUFFIX = ".tmp";
  public static final int BUFFER_SIZE = 64 * 1024;
  private String path;
  private long size;
  private String md5;
  private long nanos;

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public long getSize() {
    return size;
  }

  public void setSize(long size) {
    this.size = size;
  }

  public String getMd5() {
    return md5;
  }

  public void setMd5(String md5) {
    this.md5 = md5;
  }

  public long getNanos() {
    return nanos;
  }

  public void setNanos(long nanos) {
    this.nanos = nanos;
  }

  @Override
  public Boolean execute(StateCommandContext context) {
    String path2 = path + TMP_FILE_SUFFIX;
    File file = new File(path2);
    if(file.exists()){

      if(file.length()==size){
        MessageDigest md = MD5.newInstance();
        byte[] buffer = new byte[BUFFER_SIZE];
        int len = 0;
        try (FileInputStream fis = new FileInputStream(file)) {
          while ((len = fis.read(buffer)) != -1) {
            md.update(buffer,0,len);
          }
          String digest = MD5.md5DigestToString(md.digest());
          if(digest.equals(md5)){
            logger.log(Level.INFO,"uploaded file size={0} path={1} use time {2}",size,path, StopWatch.formatDuration(nanos));
            Files.move(file.toPath(), Paths.get(path), StandardCopyOption.REPLACE_EXISTING);
            return true;
          }else{
            logger.log(Level.WARN,"uploaded file md5 error. md5={0} expect={1} path={2}",md5, digest, path);
          }

        } catch (IOException e) {
          logger.log(Level.WARN,e);
        }
      }else{
        logger.log(Level.WARN,"uploaded file size error. size={0} expect={1} path={2}",file.length(), size, path);
      }
    }
    return false;
  }
}
