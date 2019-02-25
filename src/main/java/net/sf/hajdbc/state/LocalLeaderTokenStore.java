package net.sf.hajdbc.state;

import java.nio.file.Paths;

/**
 * Leader toeken local store
 * path: ${user.dir}/data/leader.token
 * @author dib
 */
public class LocalLeaderTokenStore extends LeaderTokenStore {
  public LocalLeaderTokenStore() {
    super(Paths.get(System.getProperty("user.dir"),"leader.token"));
  }

  public static void main(String[] args) {
    LocalLeaderTokenStore store = new LocalLeaderTokenStore();
    LeaderToken token = store.getToken();

    System.out.println("token = " + token);
  }
}
