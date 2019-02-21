package net.sf.hajdbc.state;


import java.util.StringTokenizer;

public class NodeState {
  public static final String SEPARATOR = "\t";
  public static final int COUNT = 3;
  private String ip;
  private long tver;
  private NodeRole role = NodeRole.Observer;

  public String getIp() {
    return ip;
  }

  public void setIp(String ip) {
    this.ip = ip;
  }

  public long getTver() {
    return tver;
  }

  public void setTver(long tver) {
    this.tver = tver;
  }

  public NodeRole getRole() {
    return role;
  }

  public void setRole(NodeRole role) {
    this.role = role;
  }

  @Override
  public String toString() {
    StringBuilder s = new StringBuilder();

    s.append(ip)
        .append(SEPARATOR)
        .append(tver)
        .append(SEPARATOR)
        .append(role);
    return  s.toString();
  }

  /**
   * Returns the NodeState by parse string.
   * @param s
   * @return NodeState
   */
  public static NodeState of(String s){
    NodeState ns = null;
    if(s!=null){
      StringTokenizer st = new StringTokenizer(s);
      if(st.countTokens()>= COUNT ){
        ns = new NodeState();
        ns.setIp(st.nextToken());
        ns.setTver(Long.parseLong(st.nextToken()));
        ns.setRole(NodeRole.valueOf(st.nextToken()));
      }
    }
    return ns;
  }
}
