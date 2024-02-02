package net.sf.hajdbc.dialect.h2;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.codec.Decoder;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.state.distributed.StateCommandContext;
import net.sf.hajdbc.state.sync.SyncCommand;
import net.sf.hajdbc.util.StopWatch;

import java.io.File;
import java.io.FileFilter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;


public class H2RunScriptCommand implements SyncCommand<Boolean> {
  static final Logger logger = LoggerFactory.getLogger(H2RunScriptCommand.class);

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
        Database database = context.getDatabaseCluster().getLocalDatabase();
        Decoder decoder = context.getDatabaseCluster().getDecoder();
        DbRestore dbRestore = new DbRestore();
        return dbRestore.restore(database, decoder, Paths.get(path).toFile());
      }
    }catch (Exception e){
      logger.log(Level.WARN, e);
    }
    return false;
  }


}
