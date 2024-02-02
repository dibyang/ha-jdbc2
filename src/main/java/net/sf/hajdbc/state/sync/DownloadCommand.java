package net.sf.hajdbc.state.sync;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.state.distributed.StateCommandContext;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class DownloadCommand<Z, D extends Database<Z>> implements SyncCommand<Z, D, Block> {
  static final Logger logger = LoggerFactory.getLogger(DownloadCommand.class);
  public static final int BLOCK_SIZE = 256 * 1024;

  public static final String TMP_FILE_SUFFIX = ".tmp";
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
  public Block execute(StateCommandContext<Z, D> context) {
    Block block = new Block();
    File file = new File(path);
    if(file.exists()&&offset<file.length()){
      block.setLength(file.length());
      try (RandomAccessFile raf = new RandomAccessFile(file,"r")){
        raf.seek(offset);

        byte[] buff = new byte[blockSize];
        int len = raf.read(buff);
        block.setData(buff);
        block.setSize(len);
      } catch (IOException e) {
        logger.log(Level.WARN,e);
      }
    }
    return block;
  }
}
