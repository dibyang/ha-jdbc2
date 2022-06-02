package net.sf.hajdbc;

import net.sf.hajdbc.codec.Decoder;
import net.sf.hajdbc.sync.SynchronizationContext;

import java.io.File;

public interface DBRestoreSupport extends DumpRestoreSupport{
  <Z, D extends Database<Z>> void backupDB(SynchronizationContext<Z,D> context, D database, Decoder decoder, File file, boolean dataOnly) throws Exception;

  <Z, D extends Database<Z>> void restoreDB(SynchronizationContext<Z,D> context, D database, Decoder decoder, File file, boolean dataOnly) throws Exception;
}
