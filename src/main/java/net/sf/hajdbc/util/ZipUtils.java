package net.sf.hajdbc.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class ZipUtils {

  /**
   * 压缩
   *
   * @param data 待压缩数据
   * @return byte[] 压缩后的数据
   */
  public static byte[] compress(byte[] data) {
    
      byte[] output = new byte[0];

      Deflater compresser = new Deflater();

      compresser.reset();
      compresser.setInput(data);
      compresser.finish();
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      try {
          byte[] buf = new byte[1024];
          while (!compresser.finished()) {
              int i = compresser.deflate(buf);
              bos.write(buf, 0, i);
          }
          output = bos.toByteArray();
      } catch (Exception e) {
          output = data;
          e.printStackTrace();
      } finally {
          try {
              bos.close();
          } catch (IOException e) {
              e.printStackTrace();
          }
      }
      compresser.end();
      return output;
  }

  /**
   * 解压缩
   *
   * @param data 待压缩的数据
   * @return byte[] 解压缩后的数据
   */
  public static byte[] decompress(byte[] data) {
      byte[] output = new byte[0];

      Inflater decompresser = new Inflater();
      decompresser.reset();
      decompresser.setInput(data);

      ByteArrayOutputStream o = new ByteArrayOutputStream();
      try {
          byte[] buf = new byte[1024];
          while (!decompresser.finished()) {
              int i = decompresser.inflate(buf);
              o.write(buf, 0, i);
          }
          output = o.toByteArray();
      } catch (Exception e) {
          output = data;
          e.printStackTrace();
      } finally {
          try {
              o.close();
          } catch (IOException e) {
              e.printStackTrace();
          }
      }

      decompresser.end();
      return output;
  }

  public static void main(String[] args) {
    
    StringBuilder s = new StringBuilder("snowolf@zlex.org;dongliang@zlex.org;zlex.dongliang@zl");
    for(int i=0;i<10;i++){
      s.append(" i=")
      .append(i)
      .append(";");
      
      test(s.toString());
    }
  }

  private static void test(String s) {
    System.out.println("");
    System.out.println("输入字符串:\t" + s);
    byte[] input = s.getBytes();
    System.out.println("输入字节长度:\t" + input.length);

    byte[] data = ZipUtils.compress(input);
    System.out.println("压缩后字节长度:\t" + data.length);

    byte[] output = ZipUtils.decompress(data);
    System.out.println("解压缩后字节长度:\t" + output.length);
    String outputStr = new String(output);
    System.out.println("输出字符串:\t" + outputStr);

  }
}
