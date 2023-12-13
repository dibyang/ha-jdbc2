package net.sf.hajdbc.distributed;

/**
 *
 */
public interface HaExt {
  /**
   * 检测网络判断是否需要切换网络
   */
  void detectNetwork();

  /**
   * 获取活动ip
   * @return 活动ip
   */
  String getActiveIp();
}
