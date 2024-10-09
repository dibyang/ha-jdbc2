package net.sf.hajdbc.state.sync;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.state.distributed.DistributedStateManager;
import net.sf.hajdbc.util.MD5;
import net.sf.hajdbc.util.StopWatch;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Paths;
import java.security.MessageDigest;

public class SyncMgrImpl implements SyncMgr{
  static final Logger logger = LoggerFactory.getLogger(SyncMgr.class);
  public static final int BLOCK_SIZE = 256 * 1024;

  private DistributedStateManager stateManager;

  public SyncMgrImpl(DistributedStateManager stateManager) {
    this.stateManager = stateManager;
  }

  @Override
  public boolean upload(Member target, File file) {
    return upload(target, file, file.getPath());
  }

  @Override
  public boolean upload(Member target, File file, String path) {
    if(target!=null) {
      StopWatch stopWatch = StopWatch.createStarted();
      MessageDigest md = MD5.newInstance();
      try (FileInputStream fis = new FileInputStream(file)) {
        byte[] buffer = new byte[BLOCK_SIZE];
        int len;
        int offset = 0;
        UploadCommand cmd = new UploadCommand();
        cmd.setPath(path);
        while ((len = fis.read(buffer)) != -1) {
          cmd.setOffset(offset);
          byte[] data = new byte[len];
          System.arraycopy(buffer,0,data,0,len);
          cmd.setData(data);
          boolean r = execute(target, cmd);
          if(r) {
            offset += len;
            md.update(buffer, 0, len);
          }else{
            return false;
          }
        }
        UploadedCommand cmd2 = new UploadedCommand();
        cmd2.setPath(path);
        cmd2.setSize(file.length());
        cmd2.setMd5(MD5.md5DigestToString(md.digest()));
        cmd2.setNanos(stopWatch.getNanoTime());
        boolean r = execute(target, cmd2);
        stopWatch.stop();
        logger.log(Level.INFO,"upload file path={0} size={1} r={2} time={3}", file.getPath(),file.length(), r, stopWatch.toString());
        return r;
      } catch (IOException e) {
        logger.log(Level.WARN,e);
      }
    }
    return false;
  }

  @Override
  public boolean download(Member target, File file) {
    return download(target, file, file.getPath());
  }

  @Override
  public boolean download(Member target, File file, String path) {
    boolean r = false;
    if(target!=null) {
      StopWatch stopWatch = StopWatch.createStarted();
      DownloadCommand downloadCommand = new DownloadCommand();
      if(path!=null){
        downloadCommand.setPath(path);
      }else{
        downloadCommand.setPath(file.getPath());
      }
      long offset = 0;
      try (RandomAccessFile raf = new RandomAccessFile(file, "rws")) {
        while (true) {
          downloadCommand.setOffset(offset);
          Block block = this.execute(target, downloadCommand);
          if(block!=null){
            if(block.getLength()>0) {
              if(block.getSize()>0&&block.getData()!=null) {
                MessageDigest md = MD5.newInstance();
                md.update(block.getData(),0, block.getSize());
                String md5 = MD5.md5DigestToString(md.digest());
                if(md5.equals(block.getMd5())) {
                  raf.seek(offset);
                  raf.write(block.getData(), 0, block.getSize());
                  offset += block.getSize();
                }else{
                  //下载失败
                  logger.log(Level.INFO,"download file path={0} fail for md5 invalid.", file.getPath());
                  break;
                }
              }
              if(file.length()>=block.getLength()){
                r = true;
                break;
              }
            }else{
              //空文件将会下载失败
              logger.log(Level.INFO,"download file path={0} fail for empty file.", file.getPath());
              break;
            }
          }else{
            //网络失败重试3次就会下载失败
            logger.log(Level.INFO,"download file path={0} fail for net fail.", file.getPath());
            break;
          }
        }
      } catch (IOException e) {
        logger.log(Level.WARN, e);
      }

      stopWatch.stop();
      logger.log(Level.INFO,"download file path={0} size={1} r={2} time={3}", file.getPath(),file.length(), r, stopWatch.toString());
    }
    return r;
  }


  @Override
  public Member getMember(Database db) {
    return stateManager.getMember(db.getIp());
  }

  @Override
  public <R> R execute(Member target, SyncCommand<R> cmd) {
    int fails = 0;
    while(fails<3) {
      R r = (R)stateManager.execute(cmd, target);
      if (r != null) {
        return r;
      } else {
        fails += 1;
      }
    }
    return null;
  }
}
