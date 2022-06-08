package net.sf.hajdbc.dialect.h2;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.codec.Decoder;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.state.distributed.StateCommandContext;
import net.sf.hajdbc.state.sync.SyncCommand;
import net.sf.hajdbc.util.StopWatch;

import java.sql.Connection;
import java.sql.Statement;


public class H2RunScriptCommand<Z, D extends Database<Z>> implements SyncCommand<Z, D> {
  static final Logger logger = LoggerFactory.getLogger(H2RunScriptCommand.class);

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
      StopWatch stopWatch = StopWatch.createStarted();
      D database = context.getDatabaseCluster().getLocalDatabase();
      Decoder decoder = context.getDatabaseCluster().getDecoder();
      final String password = database.decodePassword(decoder);
      try(Connection connection = database.connect(database.getConnectionSource(), password);
          Statement statTarget = connection.createStatement())
      {
        statTarget.execute("DROP ALL OBJECTS");
        statTarget.execute("RUNSCRIPT FROM '"+path+"'");
        stopWatch.stop();
        logger.log(Level.INFO,"H2 Run Script use time {0} from {1}", stopWatch.toString(), path);
      }catch (Exception e){
        //ignore Exception
      }
      return true;
    }catch (Exception e){
      logger.log(Level.WARN,e);
    }
    return false;
  }

}
