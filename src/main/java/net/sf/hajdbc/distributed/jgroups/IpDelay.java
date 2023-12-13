package net.sf.hajdbc.distributed.jgroups;


public class IpDelay implements Comparable<IpDelay>{
  private final String ip;
  private long delay;

  public IpDelay(String ip) {
    this.ip = ip;
  }

  public String getIp() {
    return ip;
  }


  public long getDelay() {
    return delay;
  }

  public void setDelay(long delay) {
    this.delay = delay;
  }

  @Override
  public int compareTo(IpDelay o) {
    if(o==null){
      return -1;
    }
    return Long.valueOf(delay).compareTo(o.delay);
  }
}
