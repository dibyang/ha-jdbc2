package net.sf.hajdbc.state.health.observer;

import net.sf.hajdbc.util.Preconditions;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class TcpLink {
  private SockAddr local;
  private SockAddr remote;

  public SockAddr getLocal() {
    return local;
  }

  public void setLocal(SockAddr local) {
    this.local = local;
  }

  public SockAddr getRemote() {
    return remote;
  }

  public void setRemote(SockAddr remote) {
    this.remote = remote;
  }

  @Override
  public String toString() {
    return "{" +
        "local=" + local +
        ", remote=" + remote +
        '}';
  }

  private static SockAddr parseSockAddr(String s){
    SockAddr sockAddr = null;
    if(s!=null){
      s = s.trim();
    }
    if(s.length()==13&&s.charAt(8)==':'){
      int port = Integer.parseInt(s.substring(9), 16);
      String addr_s = s.substring(0, 8);
      ByteBuffer buffer = ByteBuffer.allocate(4);
      buffer.order(ByteOrder.nativeOrder());
      buffer.put((byte)Integer.parseInt(addr_s.substring(0,2),16));
      buffer.put((byte)Integer.parseInt(addr_s.substring(2,4),16));
      buffer.put((byte)Integer.parseInt(addr_s.substring(4,6),16));
      buffer.put((byte)Integer.parseInt(addr_s.substring(6,8),16));
      buffer.rewind();
      int addr = buffer.getInt();
      sockAddr = new SockAddr();
      sockAddr.setPort(port);
      sockAddr.setIp(getInet4Address(toByteArray(addr)).getHostAddress());
    }
    return sockAddr;
  }
  private static byte[] toByteArray(int value) {
    return new byte[] {
        (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value
    };
  }

  private static Inet4Address getInet4Address(byte[] bytes) {
    Preconditions.checkArgument(
        bytes.length == 4,
        "Byte array has invalid length for an IPv4 address: %s != 4.",
        bytes.length);

    // Given a 4-byte array, this cast should always succeed.
    return (Inet4Address)bytesToInetAddress(bytes);
  }

  private static InetAddress bytesToInetAddress(byte[] addr) {
    try {
      return InetAddress.getByAddress(addr);
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }

  public static boolean isHostReachable(String host, int timeOut) {
    try {
      return InetAddress.getByName(host).isReachable(timeOut);
    } catch (UnknownHostException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return false;
  }

  public static List<TcpLink> readTcpLinks(String path){
    List<TcpLink> tcpLinks = new ArrayList<>();
    try {
      List<String> lines = Files.readAllLines(Paths.get(path));
      lines.remove(0);
      for (String line : lines) {
        String[] vals = line.trim().split("\\s+");
        if(vals.length>4){
          if(ConnState.TCP_ESTABLISHED.getCode()==Integer.parseInt(vals[3],16)){
            SockAddr local = parseSockAddr(vals[1]);
            if(local!=null){
              SockAddr remote = parseSockAddr(vals[2]);
              TcpLink  tcpLink = new TcpLink();
              tcpLink.setLocal(local);
              tcpLink.setRemote(remote);
              tcpLinks.add(tcpLink);
            }
          }
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return tcpLinks;
  }

}
