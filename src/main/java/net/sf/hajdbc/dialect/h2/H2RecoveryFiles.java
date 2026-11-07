package net.sf.hajdbc.dialect.h2;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** H2 恢复记录的目录持久化边界；Linux 确认文件名替换落盘后才能确认准备或决定。 */
final class H2RecoveryFiles {
  private H2RecoveryFiles() { }

  static void createDirectories(Path directory) throws IOException {
    Path absolute = directory.toAbsolutePath().normalize();
    if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid H2 recovery directory");
      return;
    }
    Path parent = absolute.getParent();
    if (parent == null) throw new IOException("Missing H2 recovery directory parent");
    createDirectories(parent);
    try { Files.createDirectory(absolute); }
    catch (java.nio.file.FileAlreadyExistsException race) {
      if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) throw race;
    }
    forceDirectory(parent);
  }

  static void forceDirectory(Path directory) throws IOException {
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid H2 recovery directory");
    // JDK8/Windows 不支持目录 FileChannel；不把该平台的进程退出测试说成断电验证。
    if (System.getProperty("os.name", "").startsWith("Windows")) return;
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      channel.force(true);
    }
  }

  static void forceBackup(Path backup) throws IOException {
    try (FileChannel channel = FileChannel.open(backup, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
      channel.force(true);
    }
    forceDirectory(backup.getParent());
  }
}
