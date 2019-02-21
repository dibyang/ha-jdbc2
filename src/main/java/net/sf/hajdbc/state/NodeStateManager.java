package net.sf.hajdbc.state;


import net.sf.hajdbc.util.BatchMap;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Node status manager
 * @author dib
 */
public class NodeStateManager {

  public static final Charset UTF_8 = Charset.forName("utf-8");
  protected final Path path ;
  private volatile long lastModified = 0;
  private final BatchMap<String,NodeState> data = new BatchMap<>();

  public NodeStateManager(Path path) {
    this.path = path;
    if(!Files.exists(path)){
      Path parent = path.getParent();
      if(!Files.exists(parent)){
        parent.toFile().mkdirs();
      }
      try {
        path.toFile().createNewFile();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  public Map<String,NodeState> getData(){
    return data.getData();
  }

  public List<NodeState> getNodeStates(){
    reload();
    List<NodeState> states = new ArrayList<>(data.getData().values());
    return states;
  }

  private void reload() {
    if(path.toFile().lastModified()!=lastModified) {
      lastModified = path.toFile().lastModified();
      try {
        List<String> lines = Files.readAllLines(path, UTF_8);
        Map<String, NodeState> map = this.data.getData();
        map.clear();
        for (String line : lines) {
          if (line != null) {
            NodeState ns = NodeState.of(line);
            if (ns != null) {
              map.put(ns.getIp(), ns);
            }
          }
        }
        data.commit(map);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

  }

  public NodeState getLeader(){
    NodeState leader = null;
    for(NodeState ns : this.getNodeStates()){
      if(NodeRole.Leader.equals(ns.getRole())){
        leader = ns;
      }
    }
    return leader;
  }

  private void save() {
    List<String> lines = new ArrayList<>();
    for(NodeState ns : this.getNodeStates()){
      lines.add(ns.toString());
    }
    try {
      Files.write(path,lines);
      lastModified = path.toFile().lastModified();
    } catch (IOException e) {
      e.printStackTrace();
    }

  }



  public NodeStateManager removeNode(String ip){
    Map<String, NodeState> map = this.data.getData();
    map.remove(ip);
    data.commit(map);
    save();
    return this;
  }

  public NodeStateManager addNode(String ip){
    Map<String, NodeState> map = this.data.getData();
    if(addNode(map, ip)){
      data.commit(map);
      save();
    }
    return this;
  }

  private boolean addNode(Map<String, NodeState> map, String ip) {
    if(!map.containsKey(ip)){
      NodeState state =  new NodeState();
      NodeState leader = this.getLeader();
      state.setIp(ip);
      if(leader != null){
        state.setTver(leader.getTver());
        state.setRole(NodeRole.Follower);
      }else{
        state.setRole(NodeRole.Observer);
      }
      map.put(state.getIp(),state);
      return true;
    }
    return false;
  }

  public NodeStateManager setRole(String ip,long tver,NodeRole role){
    Map<String, NodeState> map = this.data.getData();
    setRole(map,ip, tver, role);
    data.commit(map);
    save();
    return this;
  }

  private void setRole(Map<String, NodeState> map, String ip, long tver, NodeRole role) {
    NodeState state = map.get(ip);
    if(state==null){
      state = new NodeState();
      state.setIp(ip);
      state.setTver(tver);
      state.setRole(role);
      map.put(state.getIp(),state);
    }else{
      if(tver>=state.getTver()){
        state.setTver(tver);
        state.setRole(role);
      }
    }
  }

  public NodeStateManager observer(){
    Map<String, NodeState> map = this.data.getData();
    for(NodeState ns : map.values()){
      ns.setRole(NodeRole.Observer);
    }
    data.commit(map);
    save();
    return this;
  }

  public NodeStateManager leader(String ip,long tver){
    Map<String, NodeState> map = this.data.getData();
    addNode(map, ip);
    for(NodeState ns : map.values()){
      ns.setTver(tver);
      if(ns.getIp().equals(ip)){
        ns.setRole(NodeRole.Leader);
      }else{
        ns.setRole(NodeRole.Follower);
      }
    }
    data.commit(map);
    save();
    return this;
  }

}
