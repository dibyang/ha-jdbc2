package net.sf.hajdbc.state.sync;

import java.io.Serializable;

public class Block implements Serializable {
  private long length;
  private byte[] data;
  private int size;
  private String md5;


  public byte[] getData() {
    return data;
  }

  public void setData(byte[] data) {
    this.data = data;
  }

  public long getLength() {
    return length;
  }

  public void setLength(long length) {
    this.length = length;
  }

  public int getSize() {
    return size;
  }

  public void setSize(int size) {
    this.size = size;
  }

  public String getMd5() {
    return md5;
  }

  public void setMd5(String md5) {
    this.md5 = md5;
  }
}
