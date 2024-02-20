package net.sf.hajdbc.state.sync;

import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.state.distributed.StateCommandContext;
import net.sf.hajdbc.util.MD5;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;

public class DownloadCommand implements SyncCommand<Block> {
  static final Logger logger = LoggerFactory.getLogger(DownloadCommand.class);
  public static final int BLOCK_SIZE = 256 * 1024;

  private String path;
  private long offset;

  private int blockSize = BLOCK_SIZE;

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public long getOffset() {
    return offset;
  }

  public void setOffset(long offset) {
    this.offset = offset;
  }

  public int getBlockSize() {
    return blockSize;
  }

  public void setBlockSize(int blockSize) {
    this.blockSize = blockSize;
  }

  @Override
  public Block execute(StateCommandContext context) {
    Block block = new Block();
    File file = new File(path);
    MessageDigest md = MD5.newInstance();
    if(file.exists()&&offset<file.length()){
      block.setLength(file.length());
      try (RandomAccessFile raf = new RandomAccessFile(file,"r")){
        raf.seek(offset);
        byte[] buffer = new byte[blockSize];
        int len = raf.read(buffer);
        block.setData(buffer);
        block.setSize(len);
        if(len>0){
          md.update(buffer, 0, len);
          block.setMd5(MD5.md5DigestToString(md.digest()));
        }
      } catch (IOException e) {
        logger.log(Level.WARN,e);
      }
    }
    return block;
  }
}
