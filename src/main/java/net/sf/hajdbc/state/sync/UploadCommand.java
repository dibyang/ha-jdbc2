package net.sf.hajdbc.state.sync;

import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.state.distributed.StateCommandContext;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class UploadCommand implements SyncCommand<Boolean> {
  static final Logger logger = LoggerFactory.getLogger(UploadCommand.class);

  public static final String TMP_FILE_SUFFIX = ".tmp";
  private String path;
  private long offset;
  private byte[] data;

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


  public byte[] getData() {
    return data;
  }

  public void setData(byte[] data) {
    this.data = data;
  }

  @Override
  public Boolean execute(StateCommandContext context) {
    String path2 = path + TMP_FILE_SUFFIX;
    File file = new File(path2);
    try {
      if(offset==0&&file.exists()){
        file.delete();
      }
      if(!file.exists()){
        file.createNewFile();
      }
    } catch (IOException e) {
      logger.log(Level.WARN,e);
    }

    try (RandomAccessFile raf = new RandomAccessFile(file,"rws")){
      raf.seek(offset);
      raf.write(data);
      return true;
    } catch (IOException e) {
      logger.log(Level.WARN,e);
    }
    return false;
  }
}
