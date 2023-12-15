package net.sf.hajdbc.util;

import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;

public enum Tracer {
  invoke,
  observe,
  db_state;
  final Logger logger = LoggerFactory.getLogger(this.getClass());
  private final File traceFile;
  private Boolean trace;

  Tracer() {
    this.traceFile = Paths.get("/etc/ha-jdbc/trace", this.name()).toFile();
  }

  public boolean isTrace(){
    if(trace==null||!trace.equals(this.traceFile.exists())){
      trace = this.traceFile.exists();
      logger.log(Level.INFO, "trace {0}={1}", this.name(), trace);
    }
    return trace;
  }
}
