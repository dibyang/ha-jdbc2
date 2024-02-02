package net.sf.hajdbc.state.sync;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.distributed.Command;
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
import java.security.MessageDigest;

public class SyncMgrImpl implements SyncMgr{
  static final Logger logger = LoggerFactory.getLogger(SyncMgr.class);
  public static final int BLOCK_SIZE = 256 * 1024;

  private DistributedStateManager stateManager;

  public SyncMgrImpl(DistributedStateManager stateManager) {
    this.stateManager = stateManager;
  }

  @Override
  public boolean upload(Database db, File file) {
    Member target = this.getMember(db);
    if(target!=null) {
      StopWatch stopWatch = StopWatch.createStarted();
      MessageDigest md = MD5.newInstance();
      try (FileInputStream fis = new FileInputStream(file)) {
        byte[] buffer = new byte[BLOCK_SIZE];
        int len = 0;
        int offset = 0;
        UploadCommand cmd = new UploadCommand();
        cmd.setPath(file.getPath());
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
        cmd2.setPath(file.getPath());
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
  public boolean download(Database db, File file) {
    boolean r = false;
    Member target = this.getMember(db);
    if(target!=null) {
      StopWatch stopWatch = StopWatch.createStarted();
      DownloadCommand downloadCommand = new DownloadCommand();
      downloadCommand.setPath(file.getPath());
      while (true) {
        Block block = this.execute(target, downloadCommand);
        if(block!=null){
          if(block.getLength()>0) {
            if(block.getSize()>0&&block.getData()!=null) {
              try (RandomAccessFile raf = new RandomAccessFile(file, "rws")) {
                raf.seek(block.getOffset());
                raf.write(block.getData(), 0, block.getSize());
              } catch (IOException e) {
                logger.log(Level.WARN, e);
              }
            }
            if(file.length()>=block.getLength()){
              r = true;
              break;
            }
          }else{
            r = true;
            break;
          }
        }else{
          break;
        }
      }
      stopWatch.stop();
      logger.log(Level.INFO,"download file path={0} size={1} r={2} time={3}", file.getPath(),file.length(), r, stopWatch.toString());
    }
    return r;
  }

  private Block execute(Member target, DownloadCommand cmd) {
    int fails = 0;
    while(fails<3) {
      Block r = (Block)stateManager.execute(cmd, target);
      if (r != null) {
        return r;
      } else {
        fails += 1;
      }
    }
    return null;
  }

  private boolean execute(Member target, Command cmd) {
    int fails = 0;
    while(fails<3) {
      Object r = stateManager.execute(cmd, target);
      if (r != null && r.equals(Boolean.TRUE)) {
        return true;
      } else {
        fails += 1;
      }
    }
    return false;
  }

  @Override
  public Member getMember(Database db) {
    return stateManager.getMember(db.getIp());
  }

  @Override
  public boolean execute(Database db, Command cmd) {
    Member member = getMember(db);
    if(member!=null) {
      return execute(member, cmd);
    }
    return false;
  }
}
