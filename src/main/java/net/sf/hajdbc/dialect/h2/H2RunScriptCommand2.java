package net.sf.hajdbc.dialect.h2;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.codec.Decoder;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.state.distributed.StateCommandContext;
import net.sf.hajdbc.state.sync.SyncCommand;
import net.sf.hajdbc.util.StopWatch;
import net.sf.hajdbc.util.ZipUtils;

import java.io.File;
import java.nio.file.Paths;


public class H2RunScriptCommand2 implements SyncCommand<Boolean> {
  static final Logger logger = LoggerFactory.getLogger(H2RunScriptCommand2.class);
  public static final String EXT_ZIP = ".zip";

  private String path;

  public String getPath() {
    return path;
  }


  public void setPath(String path) {
    this.path = path;
  }

  @Override
  public Boolean execute(StateCommandContext context) {
    try {
      if(path!=null) {
        File file = Paths.get(path).toFile();
        File zipFile = Paths.get(file.getPath() + EXT_ZIP).toFile();
        if(file.exists()||zipFile.exists()) {
          Database database = context.getDatabaseCluster().getLocalDatabase();
          Decoder decoder = context.getDatabaseCluster().getDecoder();
          DbRestore dbRestore = new DbRestore();
          return dbRestore.restore(database, decoder, file);
        }
      }
    }catch (Exception e){
      logger.log(Level.WARN, e);
    }
    return false;
  }


}
