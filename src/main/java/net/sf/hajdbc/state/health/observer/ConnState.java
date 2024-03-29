package net.sf.hajdbc.state.health.observer;

public enum ConnState {
  TCP_ESTABLISHED(1),
  TCP_SYN_SENT(2),
  TCP_SYN_RECV(3),
  TCP_FIN_WAIT1(4),
  TCP_FIN_WAIT2(5),
  TCP_TIME_WAIT(6),
  TCP_CLOSE(7),
  TCP_CLOSE_WAIT(8),
  TCP_LAST_ACL(9),
  TCP_LISTEN(10),
  TCP_CLOSING(11);
  private int code;

  ConnState(int code) {
    this.code = code;
  }

  public int getCode() {
    return code;
  }

}
