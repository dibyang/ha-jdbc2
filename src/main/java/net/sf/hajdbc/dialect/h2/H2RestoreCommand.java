package net.sf.hajdbc.dialect.h2;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.state.distributed.StateCommandContext;
import net.sf.hajdbc.state.sync.SyncCommand;
import org.h2.engine.Constants;
import org.h2.message.DbException;
import org.h2.store.fs.FileUtils;
import org.h2.util.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;




public class H2RestoreCommand<Z, D extends Database<Z>> implements SyncCommand<Z, D> {
  static final Logger logger = LoggerFactory.getLogger(H2RestoreCommand.class);

  private String path;
  private String dir;
  private String db;

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public String getDir() {
    return dir;
  }

  public void setDir(String dir) {
    this.dir = dir;
  }

  public String getDb() {
    return db;
  }

  public void setDb(String db) {
    this.db = db;
  }

  @Override
  public Boolean execute(StateCommandContext<Z, D> context) {
    try {
      execute(path, dir, db);
      logger.log(Level.INFO,"H2 Restore dir={0} db={1} from {2}",dir,db,path);
      return true;
    }catch (Exception e){
      logger.log(Level.WARN,e);
    }
    return false;
  }

  void execute(String zipFileName, String directory, String db) {
    InputStream in = null;
    try {
      if (!FileUtils.exists(zipFileName)) {
        throw new IOException("File not found: " + zipFileName);
      }
      String originalDbName = null;
      int originalDbLen = 0;
      if (db != null) {
        originalDbName = getOriginalDbName(zipFileName, db);
        if (originalDbName == null) {
          throw new IOException("No database named " + db + " found");
        }
        if (originalDbName.startsWith(File.separator)) {
          originalDbName = originalDbName.substring(1);
        }
        originalDbLen = originalDbName.length();
      }
      in = FileUtils.newInputStream(zipFileName);
      try (ZipInputStream zipIn = new ZipInputStream(in)) {
        while (true) {
          ZipEntry entry = zipIn.getNextEntry();
          if (entry == null) {
            break;
          }
          String fileName = entry.getName();
          // restoring windows backups on linux and vice versa
          fileName = nameSeparatorsToNative(fileName);
          if (fileName.startsWith(File.separator)) {
            fileName = fileName.substring(1);
          }
          boolean copy = false;
          if (db == null) {
            copy = true;
          } else if (fileName.startsWith(originalDbName + ".")) {
            fileName = db + fileName.substring(originalDbLen);
            copy = true;
          }
          if (copy) {
            OutputStream o = null;
            try {
              o = FileUtils.newOutputStream(directory + File.separatorChar + fileName, false);
              IOUtils.copy(zipIn, o);
              o.close();
            } finally {
              IOUtils.closeSilently(o);
            }
          }
          zipIn.closeEntry();
        }
        zipIn.closeEntry();
      }
    } catch (IOException e) {
      throw DbException.convertIOException(e, zipFileName);
    } finally {
      IOUtils.closeSilently(in);
    }
  }

  private String getOriginalDbName(String fileName, String db)
      throws IOException {

    try (InputStream in = FileUtils.newInputStream(fileName)) {
      ZipInputStream zipIn = new ZipInputStream(in);
      String originalDbName = null;
      boolean multiple = false;
      while (true) {
        ZipEntry entry = zipIn.getNextEntry();
        if (entry == null) {
          break;
        }
        String entryName = entry.getName();
        zipIn.closeEntry();
        String name = getDatabaseNameFromFileName(entryName);
        if (name != null) {
          if (db.equals(name)) {
            originalDbName = name;
            // we found the correct database
            break;
          } else if (originalDbName == null) {
            originalDbName = name;
            // we found a database, but maybe another one
          } else {
            // we have found multiple databases, but not the correct
            // one
            multiple = true;
          }
        }
      }
      zipIn.close();
      if (multiple && !db.equals(originalDbName)) {
        throw new IOException("Multiple databases found, but not " + db);
      }
      return originalDbName;
    }
  }

  private String getDatabaseNameFromFileName(String fileName) {
    if (fileName.endsWith(Constants.SUFFIX_MV_FILE)) {
      return fileName.substring(0,
          fileName.length() - Constants.SUFFIX_MV_FILE.length());
    }
    return null;
  }

  public static String nameSeparatorsToNative(String path) {
    return File.separatorChar == '/' ? path.replace('\\', '/') : path.replace('/', '\\');
  }
}
