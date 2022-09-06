package net.sf.hajdbc.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class MD5 {
  private static final String MD5_NAME = "MD5";

  private MD5() {
  }

  public static MessageDigest newInstance() {
    try {
      return MessageDigest.getInstance(MD5_NAME);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);              // support for MD5 is required so this should not happen
    }
  }

  public static String md5DigestToString(byte[] digestBuf) {
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < digestBuf.length; i++) {
      sb.append(Integer.toHexString(0xFF & digestBuf[i]));
    }
    return sb.toString();
  }
}
