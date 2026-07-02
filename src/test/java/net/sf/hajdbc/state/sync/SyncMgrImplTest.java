package net.sf.hajdbc.state.sync;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedList;
import java.util.Queue;

import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.state.distributed.DistributedStateManager;
import net.sf.hajdbc.util.MD5;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.mockito.Mockito.mock;

public class SyncMgrImplTest
{
  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void downloadReplacesLongerLocalFileWithShorterRemoteFile() throws Exception
  {
    File file = this.temporaryFolder.newFile("target.bin");
    Files.write(file.toPath(), bytes("abcdef"));
    TestSyncMgr syncMgr = new TestSyncMgr(block("xy", 2, true));

    boolean downloaded = syncMgr.download(mock(Member.class), file, "remote.bin");

    Assert.assertTrue(downloaded);
    Assert.assertEquals("xy", new String(Files.readAllBytes(file.toPath()), "UTF-8"));
    Assert.assertFalse(new File(file.getPath() + SyncFilePath.TMP_FILE_SUFFIX).exists());
  }

  @Test
  public void downloadKeepsLocalFileWhenBlockMd5IsInvalid() throws Exception
  {
    File file = this.temporaryFolder.newFile("target.bin");
    Files.write(file.toPath(), bytes("abcdef"));
    TestSyncMgr syncMgr = new TestSyncMgr(block("xy", 2, false));

    boolean downloaded = syncMgr.download(mock(Member.class), file, "remote.bin");

    Assert.assertFalse(downloaded);
    Assert.assertEquals("abcdef", new String(Files.readAllBytes(file.toPath()), "UTF-8"));
    Assert.assertFalse(new File(file.getPath() + SyncFilePath.TMP_FILE_SUFFIX).exists());
  }

  private static byte[] bytes(String value) throws Exception
  {
    return value.getBytes("UTF-8");
  }

  private static Block block(String value, long length, boolean validMd5) throws Exception
  {
    byte[] data = bytes(value);
    Block block = new Block();
    block.setLength(length);
    block.setData(data);
    block.setSize(data.length);
    block.setMd5(validMd5 ? MD5.md5DigestToString(MD5.newInstance().digest(data)) : "invalid");
    return block;
  }

  private static class TestSyncMgr extends SyncMgrImpl
  {
    private final Queue<Block> blocks = new LinkedList<Block>();

    TestSyncMgr(Block... blocks)
    {
      super(mock(DistributedStateManager.class));
      for (Block block : blocks)
      {
        this.blocks.add(block);
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R execute(Member target, SyncCommand<R> cmd)
    {
      if (cmd instanceof DownloadCommand)
      {
        return (R) this.blocks.poll();
      }
      return null;
    }
  }
}
