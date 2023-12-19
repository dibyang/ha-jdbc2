package net.sf.hajdbc.state.distributed;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import java.util.concurrent.CopyOnWriteArrayList;
import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.Messages;
import net.sf.hajdbc.distributed.Command;
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
import net.sf.hajdbc.state.health.ClusterHealth;
import net.sf.hajdbc.state.health.ClusterHealthImpl;
import net.sf.hajdbc.state.sync.SyncMgr;
import net.sf.hajdbc.state.sync.SyncMgrImpl;
import net.sf.hajdbc.util.StopWatch;

/**
 * @author Paul Ferraro
 */
public class DistributedStateManager<Z, D extends Database<Z>> implements StateManager, DistributedManager<Z,D>, StateCommandContext<Z, D>, MembershipListener, Stateful
{
	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	private final DatabaseCluster<Z, D> cluster;
	private final StateManager stateManager;


  public CommandDispatcher<StateCommandContext<Z, D>> getDispatcher() {
    return dispatcher;
  }

  private final CommandDispatcher<StateCommandContext<Z, D>> dispatcher;
	private final ConcurrentMap<Member, Map<InvocationEvent, Map<String, InvokerEvent>>> remoteInvokerMap = new ConcurrentHashMap<Member, Map<InvocationEvent, Map<String, InvokerEvent>>>();
	private final Set<Member> members = Collections.newSetFromMap(new ConcurrentHashMap<Member, Boolean>());
  private final List<MembershipListener> membershipListeners = new CopyOnWriteArrayList<>();
  private final Map<String,Object> extContexts = new HashMap<>();
	private final ClusterHealth health;
	private final SyncMgr syncMgr;


	public DistributedStateManager(DatabaseCluster<Z, D> cluster, CommandDispatcherFactory dispatcherFactory) throws Exception {
		this.cluster = cluster;
		this.stateManager = cluster.getStateManager();
		StateCommandContext<Z, D> context = this;
		this.dispatcher = dispatcherFactory.createCommandDispatcher(cluster.getId() + ".state", context, this, this);
		this.health = new ClusterHealthImpl(this);
		this.syncMgr = new SyncMgrImpl(this);
		this.addMembershipListener(this.health);
	}

  public ClusterHealth getHealth() {
    return health;
  }

	public SyncMgr getSyncMgr() {
		return syncMgr;
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
		Set<String> activeDatabases = getActiveDatabases();
		logger.log(Level.INFO,"sync activeDatabases={0}", activeDatabases);
		DatabasesEvent event2 = new DatabasesEvent(activeDatabases);
		this.dispatcher.executeAll(new SyncActiveDbsCommand<Z, D>(event2), this.dispatcher.getLocal());

	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.DatabaseClusterListener#deactivated(net.sf.hajdbc.state.DatabaseEvent)
	 */
	@Override
	public void deactivated(DatabaseEvent event)
	{
		StopWatch stopWatch = StopWatch.createStarted();
		this.stateManager.deactivated(event);
		this.dispatcher.executeAll(new DeactivationCommand<Z, D>(event), this.dispatcher.getLocal());
		logger.log(Level.INFO,"{0} deactivated cost time:{1}", event.getSource(), stopWatch.toString());
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
		this.health.start();

	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.Lifecycle#stop()
	 */
	@Override
	public void stop()
	{
    this.health.stop();
		this.dispatcher.stop();
		this.stateManager.stop();
	}


	@Override
	public boolean isEnabled()
	{
		return this.stateManager.isEnabled() && dispatcher.getLocal().equals(dispatcher.getCoordinator());
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

	@Override
	public <R> Map<Member, R> executeAll(Command<R, StateCommandContext<Z, D>> command,
			Member... excludedMembers) {
		return dispatcher.executeAll(command, excludedMembers);
	}


	@Override
	public <R> R execute(Command<R, StateCommandContext<Z, D>> command, Member member) {
		return dispatcher.execute(command, member);
	}

	@Override
	public <C> C getExtContext(String key) {
		return (C)extContexts.get(key);
	}

	@Override
	public <C> C removeExtContext(String key) {
		return (C)extContexts.remove(key);
	}

	@Override
	public <C> void setExtContext(String key,C context) {
		if(context!=null){
			extContexts.put(key,context);
		}
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

		this.remoteInvokerMap.putIfAbsent(member, new HashMap<InvocationEvent, Map<String, InvokerEvent>>());
		members.add(member);

		checkMemberDatabaseConfig(member);

		Iterator<MembershipListener> iterator = membershipListeners.iterator();
    while(iterator.hasNext()){
      try {
        iterator.next().added(member);
      }catch (Exception e){
        logger.log(Level.WARN,e);
      }
    }

	}

	/*
	public void checkDatabaseConfig() {
		Iterator<Member> iterator = members.iterator();
		while(iterator.hasNext()){
			Member member = iterator.next();
			checkMemberDatabaseConfig(member);
		}
	}//*/
	GetDatabaseCommand getDatabaseCommand = new GetDatabaseCommand();
	private void checkMemberDatabaseConfig(Member member) {
		if(!member.equals(getLocal())){
			String ip = getIp(member);
			D database = cluster.getDatabaseByIp(ip);
			if(database==null){
				D db = (D)execute(getDatabaseCommand, member);
				if(db!=null){
					db.setLocal(false);
					cluster.addDatabase(db);
				}
			}
		}
	}


	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.distributed.MembershipListener#removed(net.sf.hajdbc.distributed.Member)
	 */
	@Override
	public void removed(Member member)
	{
		logger.log(Level.INFO,"DSM member removed:"+member);

		if (this.dispatcher.getLocal().equals(this.dispatcher.getCoordinator()))
		{
			Map<InvocationEvent, Map<String, InvokerEvent>> invokers = this.remoteInvokerMap.remove(member);
			
			if (invokers != null)
			{
				this.cluster.getDurability().recover(invokers);
			}
		}

		members.remove(member);

		Iterator<MembershipListener> iterator = membershipListeners.iterator();
    while(iterator.hasNext()){
      try {
        iterator.next().removed(member);
      }catch (Exception e){
        logger.log(Level.WARN,e);
      }
    }

  }

	private void removeNodeDatabase(Member member) {
		StopWatch stopWatch = StopWatch.createStarted();
		D database = this.cluster.getDatabaseByIp(getIp(member));
		try {
			this.cluster.deactivate(database, this.cluster.getStateManager());
			logger.log(Level.INFO, "remove NodeDatabase, cost time: {0}", stopWatch.toString());
		} catch (Exception e) {
			logger.log(Level.WARN, e, "remove NodeDatabase fail, cost time {0}:", stopWatch.toString());
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
		Set<String> ips = getIps();
		if(ips.contains(database.getIp())){
     return true;
		}
		return false;
	}

  public Member getMember(String ip) {
    Member find = null;
    Iterator<Member> iterator = members.iterator();
    while(iterator.hasNext()){
      Member next = iterator.next();
      if(getIp(next).equals(ip)){
        find = next;
        break;
      }
    }
    return find;
  }

	private Set<String> getIps() {
		Set<String> ips = new HashSet<>();
		for(Member m:this.members){
      ips.add(getIp(m));

		}
		return ips;
	}


	private String getIp(Member local) {
		return org.jgroups.util.UUID.get(((AddressMember)local).getAddress());
	}

  public Member getCoordinator() {
    return this.dispatcher.getCoordinator();
  }

	@Override
	public List<Member> getMembers() {
		return new ArrayList<>(members);
	}

  @Override
  public void addMembershipListener(MembershipListener listener) {
    membershipListeners.add(listener);
  }

  @Override
  public void removeMembershipListener(MembershipListener listener) {
    membershipListeners.remove(listener);
  }

  public Member getLocal() {
    return this.dispatcher.getLocal();
  }

  public String getLocalIp() {
    return getIp(getLocal());
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
