package net.sf.hajdbc.util;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class LocalHost {  
  
  public static String getFirstRemoteIp(String... ips){
    List<String> lips = getRemoteIp(ips);
    if(!lips.isEmpty()){
      return lips.get(0);
    }
    return null;
  }
  
  public static String getFirstRemoteIp(Collection<String> ips){
    return getFirstRemoteIp(new ArrayList(ips));
  }
  
  public static String getFirstLocalIp(String... ips){
    List<String> lips = getLocalIp(ips);
    if(!lips.isEmpty()){
      return lips.get(0);
    }
    return null;
  }
  
  public static String getFirstLocalIp(Collection<String> ips){
    return getFirstLocalIp(new ArrayList(ips));
  }
  
  public static List<String> getLocalIp(String... ips){
    List<String> lips = getLocalIp(Arrays.asList(ips));
    return lips;
  }
  
  public static List<String> getLocalIp(Collection<String> ips){
    List<String> lips = new ArrayList();
    Set<String> all_lips =  getAllIp();
    for(String ip :ips){
      if(!isNullOrEmpty(ip) && all_lips.contains(ip)){
        lips.add(ip);
      }
    }
    return lips;
  }

  private static boolean isNullOrEmpty(String ip) {
    return ip == null || ip.isEmpty();
  }

  public static List<String> getRemoteIp(String... ips){
    List<String> lips = getRemoteIp(Arrays.asList(ips));
    return lips;
  }
  
  public static List<String> getRemoteIp(Collection<String> ips){
    List<String> lips = new ArrayList();
    Set<String> all_lips =  getAllIp();
    for(String ip :ips){
      if(!isNullOrEmpty(ip)&&!all_lips.contains(ip)){
        lips.add(ip);
      }
    }
    return lips;
  }
  
  public static Set<String> getAllIp() {
    Set<String> ips = new LinkedHashSet();
    Set<InetAddress> addrs = getAllInetAddress();
    for(InetAddress addr:addrs){
      ips.add(addr.getHostAddress());
    }
    return ips;
  }
  
  public static Set<String> getAllIpv4() {
    Set<String> ips = new LinkedHashSet();
    Set<Inet4Address> addrs = getAllInet4Address();
    for(InetAddress addr:addrs){
      ips.add(addr.getHostAddress());
    }
    return ips;
  }
  
  public static Set<String> getAllIpv6() {
    Set<String> ips = new LinkedHashSet();
    Set<Inet6Address> addrs = getAllInet6Address();
    for(InetAddress addr:addrs){
      ips.add(addr.getHostAddress());
    }
    return ips;
  }
  
  public static Set<Inet4Address> getAllInet4Address() {
    Set<Inet4Address> ipv4s = new LinkedHashSet();
    Set<InetAddress> addrs = getAllInetAddress();
    for(InetAddress addr:addrs){
      if(addr instanceof Inet4Address){
        ipv4s.add((Inet4Address)addr);
      }
    }
    return ipv4s;
  }
  
  public static Set<Inet6Address> getAllInet6Address() {
    Set<Inet6Address> ipv6s = new LinkedHashSet();
    Set<InetAddress> addrs = getAllInetAddress();
    for(InetAddress addr:addrs){
      if(addr instanceof Inet6Address){
        ipv6s.add((Inet6Address)addr);
      }
    }
    return ipv6s;
  }
  
  public static Set<InetAddress> getAllInetAddress() {
    Set<InetAddress> addrs = new LinkedHashSet();
    try {
      Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
      while (nifs.hasMoreElements()) {
        NetworkInterface nif = nifs.nextElement();
        if (nif.isUp()) {
          Enumeration<InetAddress> enumAddr = nif.getInetAddresses();
          while (enumAddr.hasMoreElements()) {
            InetAddress addr = enumAddr.nextElement();
            addrs.add(addr);
          }
        }
      }
    } catch (SocketException e) {
      e.printStackTrace();
    }
    return addrs;
  }
}
