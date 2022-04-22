package net.sf.hajdbc.state.health;

public interface HaArbiter {
  long getToken();
  long updateToken(long token);
}
