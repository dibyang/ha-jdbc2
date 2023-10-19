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


public class H2RunScriptCommand<Z, D extends Database<Z>> implements SyncCommand<Z, D> {
  static final Logger logger = LoggerFactory.getLogger(H2RunScriptCommand.class);
  static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
  public static final int MAX_BACKUP_COUNT = 30;
  private String path;

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  @Override
  public Boolean execute(StateCommandContext<Z, D> context) {
    try {
      if(path!=null) {
        StopWatch stopWatch = StopWatch.createStarted();
        D database = context.getDatabaseCluster().getLocalDatabase();
        Decoder decoder = context.getDatabaseCluster().getDecoder();
        final String password = database.decodePassword(decoder);
        try (Connection connection = database.connect(database.getConnectionSource(), password);
             Statement statTarget = connection.createStatement()) {

          String bakPath = getBakPath(database.getLocation());
          statTarget.execute("BACKUP TO  '" + bakPath + "'");
          logger.log(Level.INFO, "H2 backup old data use time {0} to {1}", stopWatch.toString(), bakPath);
          statTarget.execute("DROP ALL OBJECTS");
          statTarget.execute("RUNSCRIPT FROM '" + path + "'");
          stopWatch.stop();
          logger.log(Level.INFO, "H2 Run Script use time {0} from {1}", stopWatch.toString(), path);
        }
        return true;
      }
    }catch (Exception e){
      logger.log(Level.WARN, e);
    }
    return false;
  }

  private String getBakPath(String location) {
    location = location.substring(location.indexOf("//") + 2);
    location = location.substring(location.indexOf("/")+1);
    int index = location.indexOf(";");
    if (index > 0) {
      location = location.substring(0, index);
    }

    String backupFlag = "_backup_";
    String bakPath = location + backupFlag + LocalDateTime.now().format(formatter) + ".zip";
    File bakFile = new File(bakPath);
    String bakFileName = bakFile.getName();
    String namePrefix = bakFileName.substring(0,bakFileName.indexOf(backupFlag)+backupFlag.length());
    File dbDir = bakFile.getParentFile();
    if(!dbDir.exists()){
      dbDir.mkdirs();
    }
    File[] files = dbDir.listFiles(new FileFilter() {
      @Override
      public boolean accept(File f) {
        return f.getName().startsWith(namePrefix) && f.getName().endsWith(".zip");
      }
    });
    if(files!=null){
      List<File> fileList = Arrays.stream(files)
          .sorted((f1, f2) -> f2.getName().compareTo(f1.getName()))
          .skip(MAX_BACKUP_COUNT).collect(Collectors.toList());
      fileList.forEach(f->{
        f.delete();
        logger.log(Level.INFO, "H2 remove backup {0}", f.getPath());
      });
    }
    return bakPath;
  }

}
