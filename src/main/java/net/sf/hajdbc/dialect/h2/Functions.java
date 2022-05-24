package net.sf.hajdbc.dialect.h2;

import java.util.UUID;

/**
 * 扩展H2自定义函数
 * 参考方法，暂时没有使用
 */
public class Functions {

  /**
   * 用法：SELECT uuid();
   * H2数据库注册uuid函数：
   * CREATE ALIAS IF NOT EXISTS uuid FOR "net.sf.hajdbc.dialect.h2.Functions.uuid";
   *
   * @return
   */
  public static String uuid() {
    return UUID.randomUUID().toString();
  }


}
