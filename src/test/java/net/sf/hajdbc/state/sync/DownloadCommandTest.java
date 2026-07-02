package net.sf.hajdbc.state.sync;

import java.io.File;

import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DownloadCommandTest
{
  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @After
  public void clearAllowedRoots()
  {
    System.clearProperty(SyncFilePath.ALLOWED_ROOTS_PROPERTY);
  }

  @Test
  public void downloadRejectsPathOutsideAllowedRoots() throws Exception
  {
    File allowedRoot = this.temporaryFolder.newFolder("allowed");
    File outsideRoot = this.temporaryFolder.newFolder("outside");
    File outsideTarget = new File(outsideRoot, "source.bin");
    System.setProperty(SyncFilePath.ALLOWED_ROOTS_PROPERTY, allowedRoot.getAbsolutePath());
    DownloadCommand command = new DownloadCommand();
    command.setPath(outsideTarget.getAbsolutePath());

    Block block = command.execute(null);

    Assert.assertEquals(0, block.getLength());
    Assert.assertNull(block.getData());
  }
}
