package net.sf.hajdbc.state.sync;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.distributed.Member;

import java.io.File;

public interface SyncMgr {
  /**
   * 上传文件（服务器端和本地路径一致）
   * @param target
   * @param file
   * @return 是否成功
   */
  boolean upload(Member target, File file);

  /**
   * 上传文件
   * @param target
   * @param file
   * @param path 服务器端文件路径
   * @return 是否成功
   */
  boolean upload(Member target, File file, String path);

  /**
   * 下载文件（服务器端和本地路径一致）
   * @param target
   * @param file 本地文件
   * @return 是否成功
   */
  boolean download(Member target, File file);

  /**
   * 下载文件
   * @param target
   * @param file 本地文件
   * @param path 服务器端文件路径
   * @return 是否成功
   */
  boolean download(Member target, File file, String path);

  /**
   * 获取数据库的地址
   * @param db
   * @return
   */
  Member getMember(Database db);

  /**
   * 执行远程命令
   * @param target
   * @param cmd
   * @return
   * @param <R>
   */
  <R> R execute(Member target, SyncCommand<R> cmd);

  /**
   * 单次发送有副作用的命令；空响应表示结果未知，禁止自动重发。
   * 旧实现默认拒绝，不能回退到可能重试的 execute。
   */
  default <R> R executeOnce(Member target, SyncCommand<R> cmd) {
    throw new UnsupportedOperationException("Single-attempt sync execution is not supported");
  }
}
