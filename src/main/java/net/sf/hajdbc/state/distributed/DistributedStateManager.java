/*
 * HA-JDBC: High-Availability JDBC
 * Copyright (C) 2012  Paul Ferraro
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.hajdbc.state.distributed;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.LeaderListener;
import net.sf.hajdbc.Messages;
import net.sf.hajdbc.distributed.CommandDispatcher;
import net.sf.hajdbc.distributed.CommandDispatcherFactory;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.distributed.MembershipListener;
import net.sf.hajdbc.distributed.Remote;
import net.sf.hajdbc.distributed.Stateful;
import net.sf.hajdbc.distributed.jgroups.AddressMember;
import net.sf.hajdbc.durability.InvocationEvent;
import net.sf.hajdbc.durability.InvokerEvent;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.state.*;
import org.jgroups.Address;
import org.jgroups.stack.IpAddress;

/**
 * @author Paul Ferraro
 */
public class DistributedStateManager<Z, D extends Database<Z>> implements StateManager, StateCommandContext<Z, D>, MembershipListener, Stateful
{
	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	private final DatabaseCluster<Z, D> cluster;
	private final StateManager stateManager;

  private LeaderManager leaderManager;
  private volatile boolean election = false;

  public CommandDispatcher<StateCommandContext<Z, D>> getDispatcher() {
    return dispatcher;
  }

  private final CommandDispatcher<StateCommandContext<Z, D>> dispatcher;
	private final ConcurrentMap<Member, Map<InvocationEvent, Map<String, InvokerEvent>>> remoteInvokerMap = new ConcurrentHashMap<Member, Map<InvocationEvent, Map<String, InvokerEvent>>>();
	private final Set<Member> members = Collections.newSetFromMap(new ConcurrentHashMap<Member, Boolean>());

	public DistributedStateManager(DatabaseCluster<Z, D> cluster, CommandDispatcherFactory dispatcherFactory) throws Exception
	{
		this.cluster = cluster;
		this.stateManager = cluster.getStateManager();
		StateCommandContext<Z, D> context = this;
		this.dispatcher = dispatcherFactory.createCommandDispatcher(cluster.getId() + ".state", context, this, this);
		this.leaderManager = new LeaderManager();
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.state.StateManager#getActiveDatabases()
	 */
	@Override
	public Set<String> getActiveDatabases()
	{
		return this.stateManager.getActiveDatabases();
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.state.StateManager#setActiveDatabases(java.util.Set)
	 */
	@Override
	public void setActiveDatabases(Set<String> databases)
	{
		this.stateManager.setActiveDatabases(databases);
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.DatabaseClusterListener#activated(net.sf.hajdbc.state.DatabaseEvent)
	 */
	@Override
	public void activated(DatabaseEvent event)
	{
		this.stateManager.activated(event);
		this.dispatcher.executeAll(new ActivationCommand<Z, D>(event), this.dispatcher.getLocal());
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.DatabaseClusterListener#deactivated(net.sf.hajdbc.state.DatabaseEvent)
	 */
	@Override
	public void deactivated(DatabaseEvent event)
	{
		this.stateManager.deactivated(event);
		this.dispatcher.executeAll(new DeactivationCommand<Z, D>(event), this.dispatcher.getLocal());
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.durability.DurabilityListener#afterInvocation(net.sf.hajdbc.durability.InvocationEvent)
	 */
	@Override
	public void afterInvocation(InvocationEvent event)
	{
		this.dispatcher.executeAll(new PostInvocationCommand<Z, D>(this.getRemoteDescriptor(event)));
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.durability.DurabilityListener#afterInvoker(net.sf.hajdbc.durability.InvokerEvent)
	 */
	@Override
	public void afterInvoker(InvokerEvent event)
	{
		this.dispatcher.executeAll(new InvokerCommand<Z, D>(this.getRemoteDescriptor(event)));
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.durability.DurabilityListener#beforeInvocation(net.sf.hajdbc.durability.InvocationEvent)
	 */
	@Override
	public void beforeInvocation(InvocationEvent event)
	{
		this.dispatcher.executeAll(new PreInvocationCommand<Z, D>(this.getRemoteDescriptor(event)));
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.durability.DurabilityListener#beforeInvoker(net.sf.hajdbc.durability.InvokerEvent)
	 */
	@Override
	public void beforeInvoker(InvokerEvent event)
	{
		this.dispatcher.executeAll(new InvokerCommand<Z, D>(this.getRemoteDescriptor(event)));
	}

	private RemoteInvocationDescriptor getRemoteDescriptor(InvocationEvent event)
	{
		return new RemoteInvocationDescriptorImpl(event, this.dispatcher.getLocal());
	}
	
	private RemoteInvokerDescriptor getRemoteDescriptor(InvokerEvent event)
	{
		return new RemoteInvokerDescriptorImpl(event, this.dispatcher.getLocal());
	}
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.Lifecycle#start()
	 */
	@Override
	public void start() throws Exception
	{
		this.stateManager.start();
		this.dispatcher.start();

	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.Lifecycle#stop()
	 */
	@Override
	public void stop()
	{
		this.dispatcher.stop();
		this.stateManager.stop();
	}

  @Override
	public boolean isLeader(){
	  return !election&& this.getLeaderManager().isLeader(getIp(this.dispatcher.getLocal()));
  }

	@Override
	public boolean isEnabled()
	{
		return this.stateManager.isEnabled() && this.dispatcher.getLocal().equals(this.dispatcher.getCoordinator());
	}


  /**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.state.distributed.StateCommandContext#getDatabaseCluster()
	 */
	@Override
	public DatabaseCluster<Z, D> getDatabaseCluster()
	{
		return this.cluster;
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.state.distributed.StateCommandContext#getLocalStateManager()
	 */
	@Override
	public StateManager getLocalStateManager()
	{
		return this.stateManager;
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.state.distributed.StateCommandContext#getRemoteInvokers(net.sf.hajdbc.distributed.Remote)
	 */
	@Override
	public Map<InvocationEvent, Map<String, InvokerEvent>> getRemoteInvokers(Remote remote)
	{
		return this.remoteInvokerMap.get(remote.getMember());
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.distributed.Stateful#readState(java.io.ObjectInput)
	 */
	@Override
	public void readState(ObjectInput input) throws IOException
	{
		if (input.available() > 0)
		{
			Set<String> databases = new TreeSet<String>();
			
			int size = input.readInt();
			
			for (int i = 0; i < size; ++i)
			{
				databases.add(input.readUTF());
			}
			
			this.logger.log(Level.INFO, Messages.INITIAL_CLUSTER_STATE_REMOTE.getMessage(databases, this.dispatcher.getCoordinator()));
			
			this.stateManager.setActiveDatabases(databases);


    }
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.distributed.Stateful#writeState(java.io.ObjectOutput)
	 */
	@Override
	public void writeState(ObjectOutput output) throws IOException
	{
		Set<D> databases = this.cluster.getBalancer();
		output.writeInt(databases.size());
		
		for (D database: databases)
		{
			output.writeUTF(database.getId());
		}
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.distributed.MembershipListener#added(net.sf.hajdbc.distributed.Member)
	 */
	@Override
	public void added(Member member)
	{
		members.add(member);
		this.remoteInvokerMap.putIfAbsent(member, new HashMap<InvocationEvent, Map<String, InvokerEvent>>());
		if(this.isEnabled()){
		  this.elect(false);
    }
	}



	private void elect(boolean recover){
    election = true;
    InquireCommand cmd = new InquireCommand();
    Member member = null;
    LeaderToken token = null;
    LeaderToken ltoken = this.getLeaderManager().getToken();
    LeaderToken rtoken = null;
    Iterator<Member> iterator = members.iterator();
    while(iterator.hasNext()){
      member = iterator.next();
      if(!dispatcher.getLocal().equals(member)){
        rtoken = (LeaderToken)dispatcher.execute(cmd, member);
        break;
      }
    }
    if(rtoken!=null){
      if(ltoken.hasLeader()){
        if(rtoken.hasLeader()&&rtoken.getTver()>ltoken.getTver()){
          token = rtoken;
        }else{
          token = ltoken;
        }
      }else{
        token = rtoken;
      }
    }else{
      if(ltoken.hasLeader()){
        token = ltoken;
      }
    }
    if(token!=null){
      //
      if(recover){
        token.setTver(token.getTver()+1000);
      }else{
        token.setTver(token.getTver()+1);
      }
    }
		LeaderCommand cmdLeader = new LeaderCommand(token);
    this.leader(token.getLeader(),token.getTver());
		if(members.size()>1){
			dispatcher.executeAll(cmdLeader,this.dispatcher.getLocal());
		}
		election = false;
  }

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.distributed.MembershipListener#removed(net.sf.hajdbc.distributed.Member)
	 */
	@Override
	public void removed(Member member)
	{
		if (this.dispatcher.getLocal().equals(this.dispatcher.getCoordinator()))
		{
			Map<InvocationEvent, Map<String, InvokerEvent>> invokers = this.remoteInvokerMap.remove(member);
			
			if (invokers != null)
			{
				this.cluster.getDurability().recover(invokers);
			}

			this.getLeaderManager().removed(getIp(member));
		}
		members.remove(member);
    if(this.isEnabled()){
      this.elect(true);
    }
	}
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.state.StateManager#recover()
	 */
	@Override
	public Map<InvocationEvent, Map<String, InvokerEvent>> recover()
	{
		return this.stateManager.recover();
	}

	@Override
	public boolean isValid(Database<?> database) {
		Set<InetAddress> ips = getIps();
		if(ips.contains(database.getIp())){
			return true;
		}
		return false;
	}

	private Set<InetAddress> getIps() {
		Set<InetAddress> ips = new HashSet<>();
		for(Member m:this.members){
			if(m instanceof AddressMember){
				Address address = ((AddressMember) m).getAddress();
				if(address instanceof IpAddress){
					ips.add(((IpAddress)address).getIpAddress());
				}
			}
		}
		return ips;
	}

  @Override
  public LeaderManager getLeaderManager() {
		if(!leaderManager.isInited()){
			try {
				Member local = dispatcher.getLocal();
				String ip = getIp(local);
				InetAddress address = InetAddress.getByName(ip);
				NetworkInterface nic = NetworkInterface.getByInetAddress(address);
				leaderManager.init(ip, nic);
			}catch (Exception ex){
				ex.printStackTrace();
			}
		}
    return leaderManager;
  }

	private String getIp(Member local) {
		return org.jgroups.util.UUID.get(((AddressMember)local).getAddress());
	}

	@Override
  public boolean leader(String leader, long tver) {
		this.getLeaderManager().leader(leader,tver);
		this.cluster.leader(this.getLeaderManager().getToken());
		return true;
  }

	private static class RemoteDescriptor implements Remote, Serializable
	{
		private static final long serialVersionUID = 3717630867671175936L;
		
		private final Member member;
		
		RemoteDescriptor(Member member)
		{
			this.member = member;
		}

		@Override
		public Member getMember()
		{
			return this.member;
		}
	}
	
	private static class RemoteInvocationDescriptorImpl extends RemoteDescriptor implements RemoteInvocationDescriptor
	{
		private static final long serialVersionUID = 7782082258670023082L;
		
		private final InvocationEvent event;
		
		RemoteInvocationDescriptorImpl(InvocationEvent event, Member member)
		{
			super(member);
			
			this.event = event;
		}
		
		@Override
		public InvocationEvent getEvent()
		{
			return this.event;
		}

		/**
		 * {@inheritDoc}
		 * @see java.lang.Object#toString()
		 */
		@Override
		public String toString()
		{
			return this.event.toString();
		}
	}
	
	private static class RemoteInvokerDescriptorImpl extends RemoteDescriptor implements RemoteInvokerDescriptor
	{
		private static final long serialVersionUID = 6991831573393882786L;
		
		private final InvokerEvent event;
		
		RemoteInvokerDescriptorImpl(InvokerEvent event, Member member)
		{
			super(member);
			
			this.event = event;
		}
		
		@Override
		public InvokerEvent getEvent()
		{
			return this.event;
		}

		/**
		 * {@inheritDoc}
		 * @see java.lang.Object#toString()
		 */
		@Override
		public String toString()
		{
			return this.event.toString();
		}
	}
}
