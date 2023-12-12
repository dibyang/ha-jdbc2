package net.sf.hajdbc.state.health.observer;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class Observer {
  private final List<ObserveAdapter> adapters = new ArrayList<>();

  public Observer() {
    ServiceLoader<ObserveAdapter> loader = ServiceLoader.load(ObserveAdapter.class);
    Iterator<ObserveAdapter> iterator = loader.iterator();
    while(iterator.hasNext()){
      adapters.add(iterator.next());
    }
    Collections.sort(adapters, new Comparator<ObserveAdapter>() {
      @Override
      public int compare(ObserveAdapter o1, ObserveAdapter o2) {
        return Integer.compare(o2.getWeight(),o1.getWeight());
      }
    });
  }

  /**
   * Observe adapters
   * @return Observe adapters
   */
  public List<ObserveAdapter> getAdapters() {
    return Collections.unmodifiableList(adapters);
  }

  /**
   * Return observable or not
   * @param localIp local ip
   * @param ips ip list
   * @return observable or not
   */
  public boolean isObservable(String localIp, List<String> ips){
    for (ObserveAdapter adapter: adapters){
      if(adapter.isObservable(localIp, ips)){
        return true;
      }
    }
    return false;
  }

  public static List<String> getIps(String path){
    List<String> ips = new ArrayList<>();
    try {
      List<String> lines = Files.readAllLines(Paths.get(path));
      for (String line : lines) {
        String[] vals = line.split(" ");
        if(vals.length>4&&vals[3].equals("06")) {

          System.out.println("vals = " + Arrays.asList(vals));
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return ips;
  }
  public static String integer2Ip(int ip) {
    StringBuilder sb = new StringBuilder();
    int num = 0;
    boolean needPoint = false; // 是否需要加入'.'
    for (int i = 0; i < 4; i++) {
      if (needPoint) {
        sb.append('.');
      }
      needPoint = true;
      int offset = 8 * (3 - i);
      num = (ip >> offset) & 0xff;
      sb.append(num);
    }
    return sb.toString();
  }

  public static void main(String[] args) throws UnknownHostException {
    //List<String> ips = getIps("d:/pp.txt");
    ByteBuffer buffer = ByteBuffer.allocate(4);
    buffer.order(ByteOrder.nativeOrder());
    buffer.put(Byte.parseByte("01",16));
    buffer.put(Byte.parseByte("00",16));
    buffer.put(Byte.parseByte("00",16));
    buffer.put(Byte.parseByte("7F",16));
    buffer.rewind();
    int i = buffer.getInt();

    System.out.println("i = " + i);
    String ip = integer2Ip(i);
    System.out.println("ip = " + ip);
  }
}
