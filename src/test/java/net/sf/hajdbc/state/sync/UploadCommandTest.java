package net.sf.hajdbc.state.sync;

import java.io.File;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class UploadCommandTest
{
  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @After
  public void clearAllowedRoots()
  {
    System.clearProperty(SyncFilePath.ALLOWED_ROOTS_PROPERTY);
  }

  @Test
  public void uploadRejectsPathOutsideAllowedRoots() throws Exception
  {
    File allowedRoot = this.temporaryFolder.newFolder("allowed");
    File outsideRoot = this.temporaryFolder.newFolder("outside");
    File outsideTarget = new File(outsideRoot, "target.bin");
    System.setProperty(SyncFilePath.ALLOWED_ROOTS_PROPERTY, allowedRoot.getAbsolutePath());
    UploadCommand command = new UploadCommand();
    command.setPath(outsideTarget.getAbsolutePath());
    command.setOffset(0);
    command.setData("data".getBytes("UTF-8"));

    Boolean uploaded = command.execute(null);

    Assert.assertFalse(uploaded);
    Assert.assertFalse(Files.exists(new File(outsideRoot, "target.bin" + SyncFilePath.TMP_FILE_SUFFIX).toPath()));
  }
}
