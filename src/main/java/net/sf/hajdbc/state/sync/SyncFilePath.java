package net.sf.hajdbc.state.sync;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 同步命令的文件路径边界。
 */
final class SyncFilePath
{
  static final String ALLOWED_ROOTS_PROPERTY = "ha-jdbc.sync.allowed-roots";
  static final String TMP_FILE_SUFFIX = ".tmp";

  private SyncFilePath()
  {
  }

  static Path target(String path)
  {
    if (path == null || path.trim().isEmpty())
    {
      throw new IllegalArgumentException("Sync path is empty");
    }
    Path target = Paths.get(path).toAbsolutePath().normalize();
    verifyAllowed(target);
    return target;
  }

  static Path tempSibling(String path)
  {
    Path target = target(path);
    Path fileName = target.getFileName();
    if (fileName == null)
    {
      throw new IllegalArgumentException("Sync path has no file name: " + path);
    }
    return target.resolveSibling(fileName.toString() + TMP_FILE_SUFFIX);
  }

  private static void verifyAllowed(Path target)
  {
    String roots = System.getProperty(ALLOWED_ROOTS_PROPERTY);
    if (roots == null || roots.trim().isEmpty())
    {
      return;
    }
    String[] values = roots.split(File.pathSeparator);
    for (String value : values)
    {
      if (!value.trim().isEmpty())
      {
        Path root = Paths.get(value).toAbsolutePath().normalize();
        if (target.startsWith(root))
        {
          return;
        }
      }
    }
    throw new IllegalArgumentException("Sync path is outside allowed roots: " + target);
  }
}
