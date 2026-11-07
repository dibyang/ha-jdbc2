package net.sf.hajdbc.dialect.h2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Linux 上实际调用目录 force，Windows 仅核对路径/文件保护，不声称覆盖断电语义。 */
public class H2RecoveryFilesTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test public void createsAndForcesEveryNewDirectoryWithoutReplacingAnExistingFile() throws Exception {
    Path directory = temporary.getRoot().toPath().resolve("a/b/c");
    H2RecoveryFiles.createDirectories(directory); H2RecoveryFiles.createDirectories(directory);
    assertTrue(Files.isDirectory(directory)); H2RecoveryFiles.forceDirectory(directory);
    Path file = directory.resolve("record"); Files.write(file, new byte[] {7});
    try { H2RecoveryFiles.createDirectories(file.resolve("bad")); fail("Accepted a file as directory"); }
    catch (IOException expected) { assertArrayEquals(new byte[] {7}, Files.readAllBytes(file)); }
  }

  @Test public void forceRejectsMissingOrNonDirectoryRatherThanConfirmingADurableRecord() throws Exception {
    Path file = temporary.newFile().toPath();
    for (Path invalid : new Path[] {file, temporary.getRoot().toPath().resolve("missing")}) {
      try { H2RecoveryFiles.forceDirectory(invalid); fail("Accepted an invalid directory"); }
      catch (IOException expected) { }
    }
  }

  @Test public void backupMustExistAndItsBytesAreUnchangedByDurablePinPreparation() throws Exception {
    Path backup = temporary.newFile("backup.dump").toPath(); byte[] bytes = {1, 2, 3}; Files.write(backup, bytes);
    H2RecoveryFiles.forceBackup(backup); assertArrayEquals(bytes, Files.readAllBytes(backup)); Files.delete(backup);
    try { H2RecoveryFiles.forceBackup(backup); fail("Confirmed a missing backup"); }
    catch (IOException expected) { }
  }
}
