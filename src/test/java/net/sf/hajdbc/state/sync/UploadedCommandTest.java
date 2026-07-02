package net.sf.hajdbc.state.sync;

import java.io.File;
import java.nio.file.Files;

import net.sf.hajdbc.util.MD5;

import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class UploadedCommandTest
{
  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @After
  public void clearAllowedRoots()
  {
    System.clearProperty(SyncFilePath.ALLOWED_ROOTS_PROPERTY);
  }

  @Test
  public void uploadedRejectsPathOutsideAllowedRoots() throws Exception
  {
    File allowedRoot = this.temporaryFolder.newFolder("allowed");
    File outsideRoot = this.temporaryFolder.newFolder("outside");
    File outsideTarget = new File(outsideRoot, "target.bin");
    System.setProperty(SyncFilePath.ALLOWED_ROOTS_PROPERTY, allowedRoot.getAbsolutePath());
    UploadedCommand command = new UploadedCommand();
    command.setPath(outsideTarget.getAbsolutePath());
    command.setSize(0);
    command.setMd5(MD5.md5DigestToString(MD5.newInstance().digest()));

    Boolean uploaded = command.execute(null);

    Assert.assertFalse(uploaded);
    Assert.assertFalse(Files.exists(outsideTarget.toPath()));
  }

  @Test
  public void uploadedDeletesTempFileWhenDigestIsInvalid() throws Exception
  {
    File root = this.temporaryFolder.newFolder("root");
    File target = new File(root, "target.bin");
    File temp = new File(root, "target.bin" + SyncFilePath.TMP_FILE_SUFFIX);
    Files.write(temp.toPath(), "bad".getBytes("UTF-8"));
    UploadedCommand command = new UploadedCommand();
    command.setPath(target.getAbsolutePath());
    command.setSize(temp.length());
    command.setMd5("invalid");

    Boolean uploaded = command.execute(null);

    Assert.assertFalse(uploaded);
    Assert.assertFalse(Files.exists(temp.toPath()));
    Assert.assertFalse(Files.exists(target.toPath()));
  }
}
