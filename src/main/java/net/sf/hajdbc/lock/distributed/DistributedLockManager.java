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
package net.sf.hajdbc.lock.distributed;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.dialect.h2.H2ActivationIntent;
import net.sf.hajdbc.dialect.h2.H2RestoreWriteFence;
import net.sf.hajdbc.distributed.CommandDispatcher;
import net.sf.hajdbc.distributed.CommandDispatcherFactory;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.distributed.MembershipListener;
import net.sf.hajdbc.distributed.Remote;
import net.sf.hajdbc.distributed.Stateful;
import net.sf.hajdbc.lock.LockManager;
import net.sf.hajdbc.lock.Locked;
import net.sf.hajdbc.lock.ReadLock;
import net.sf.hajdbc.lock.WriteLock;
import net.sf.hajdbc.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Paul Ferraro
 */
public class DistributedLockManager implements LockManager, LockCommandContext, Stateful, MembershipListener
{
	static final  Logger LOG = LoggerFactory.getLogger(DistributedLockManager.class);
	final CommandDispatcher<LockCommandContext> dispatcher;
	
	private final LockManager lockManager;
	private final ConcurrentMap<Member, Map<LockDescriptor, Lock>> remoteLockDescriptorMap = new ConcurrentHashMap<Member, Map<LockDescriptor, Lock>>();
	// 每目标只保留最新恢复锁；旧代次由持久记录拒绝，避免无限保存历史描述符。
	private final Map<String, RestoreBinding> restoreFences = new HashMap<>();

	/** 恢复命令的专用准备入口；确认前取得本地写锁并持久记录未决激活。 */
	public H2RestoreWriteFence prepareRestoreLock(RestoreLockDescriptor descriptor, H2ActivationIntent intent,
		String owner, String digest, Set<String> participants) throws IOException {
		return prepareRestoreLock(descriptor, intent, owner, owner, "local", digest, participants);
	}

	/** 双通道恢复准备使用的完整持久身份。 */
	public H2RestoreWriteFence prepareRestoreLock(RestoreLockDescriptor descriptor, H2ActivationIntent intent,
		String owner, String ownerDatabase, String membership, String digest, Set<String> participants) throws IOException {
		return prepareRestoreLock(descriptor, intent, owner, ownerDatabase, membership, digest, participants, Collections.emptySet());
	}
	public H2RestoreWriteFence prepareRestoreLock(RestoreLockDescriptor descriptor, H2ActivationIntent intent,
		String owner, String ownerDatabase, String membership, String digest, Set<String> participants, Set<String> databases) throws IOException {
		synchronized (restoreFences) {
			RestoreBinding previous = restoreFences.get(descriptor.getDatabase());
			if (previous != null && previous.intent != intent) throw new IOException("Restore intent owner changed");
			if (previous != null && previous.descriptor.equals(descriptor)) {
				if (previous.intent != intent) throw new IOException("Restore intent owner changed");
				previous.fence.prepare(descriptor.getEpoch(), descriptor.getOperation(), owner, ownerDatabase, membership, digest, participants, databases);
				return previous.fence;
			}
			if (previous != null && (previous.fence.isHeld() || previous.fence.isUnresolved()))
				throw new IOException("Previous recovery activation is unresolved");
			// 先核对持久代次再创建锁对象，迟到请求不能重新创建已完成的锁。
			H2ActivationIntent.Snapshot snapshot = intent.snapshot();
			if (descriptor.getEpoch() <= snapshot.epoch && !descriptor.getOperation().equals(snapshot.operation))
				throw new IOException("Stale recovery lock");
			WriteLock raw = (WriteLock) getLock(descriptor);
			H2RestoreWriteFence fence = previous == null ? new H2RestoreWriteFence(intent, raw)
				: previous.fence.nextOperation(raw);
			RestoreBinding binding = new RestoreBinding(descriptor, intent, fence);
			restoreFences.put(descriptor.getDatabase(), binding);
			if (previous != null) getLocks(previous.descriptor.getMember()).remove(previous.descriptor);
			fence.prepare(descriptor.getEpoch(), descriptor.getOperation(), owner, ownerDatabase, membership, digest, participants, databases);
			return fence;
		}
	}

	/** 旧获取锁命令不能重新获取恢复锁；只允许核对已准备且仍持有的同一身份。 */
	public boolean isRestoreLockHeld(RestoreLockDescriptor descriptor) {
		RestoreBinding binding;
		synchronized (restoreFences) {
			binding = restoreFences.get(descriptor.getDatabase());
		}
		return binding != null && binding.descriptor.equals(descriptor) && binding.fence.isHeld();
	}

	/** 恢复协议使用锁通道自己的视图和所有者，不能用状态通道地址冒充。 */
	public net.sf.hajdbc.distributed.MembershipSnapshot membershipSnapshot() {
		return dispatcher.membershipSnapshot();
	}

	public Member getLocalMember() { return dispatcher.getLocal(); }

	/** 成员离开和旧释放消息都不得绕过持久未决状态。 */
	public boolean requestRestoreRelease(RestoreLockDescriptor descriptor) {
		RestoreBinding binding;
		synchronized (restoreFences) {
			binding = restoreFences.get(descriptor.getDatabase());
		}
		if (binding == null || !binding.descriptor.equals(descriptor)) return false;
		try {
			return binding.fence.requestRelease(descriptor.getEpoch(), descriptor.getOperation());
		} catch (IOException failure) {
			LOG.error("Keep unresolved H2 recovery write fence: {}", descriptor, failure);
			return false;
		}
	}

	private static final class RestoreBinding {
		final RestoreLockDescriptor descriptor;
		final H2ActivationIntent intent;
		final H2RestoreWriteFence fence;
		RestoreBinding(RestoreLockDescriptor descriptor, H2ActivationIntent intent, H2RestoreWriteFence fence) {
			this.descriptor = descriptor; this.intent = intent; this.fence = fence;
		}
	}


	public <Z, D extends Database<Z>> DistributedLockManager(DatabaseCluster<Z, D> cluster, CommandDispatcherFactory dispatcherFactory) throws Exception
	{
		this.lockManager = cluster.getLockManager();
		LockCommandContext context = this;
		this.dispatcher = dispatcherFactory.createCommandDispatcher(cluster.getId() + ".lock", context, this, this);

	}
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.lock.LockManager#readLock(java.lang.String)
	 */
	@Override
	public Lock readLock(String id)
	{
		//return this.lockManager.readLock(id);
		RemoteLockDescriptor descriptor = new RemoteLockDescriptorImpl(id, LockType.READ, this.dispatcher.getLocal());
		//return this.getDistibutedLock(descriptor);
		return this.getLock(descriptor);
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.lock.LockManager#writeLock(java.lang.String)
	 */
	@Override
	public Lock writeLock(String id)
	{
		return this.getDistibutedLock(new RemoteLockDescriptorImpl(id, LockType.WRITE, this.dispatcher.getLocal()));
	}

	@Override
	public Lock onlyLock(String id) {
		return this.dispatcher.getLockService().getLock(id);
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.lock.distributed.LockCommandContext#getLock(net.sf.hajdbc.lock.distributed.LockDescriptor)
	 */
	@Override
	public Lock getLock(LockDescriptor lockDescriptor)
	{
		RemoteLockDescriptor descriptor = (RemoteLockDescriptor) lockDescriptor;
		Map<LockDescriptor, Lock> locks = this.getLocks(descriptor.getMember());
		synchronized (locks) {
			Lock lock = locks.get(descriptor);
			if(lock==null){
				Lock rawLock = getRawLock(lockDescriptor);
				if(rawLock instanceof ReadLock){
					lock = new NodeReadLock((ReadLock) rawLock);
				}
				if(rawLock instanceof WriteLock){
					lock = new NodeWriteLock((WriteLock) rawLock);
				}
				locks.put(descriptor, lock);
			}
			return lock;
		}
	}

	private Lock getRawLock(LockDescriptor descriptor) {
		String id = descriptor.getId();

		switch (descriptor.getType())
		{
			case READ:
			{
				return this.lockManager.readLock(id);
			}
			case WRITE:
			{
				return this.lockManager.writeLock(id);
			}
			default:
			{
				throw new IllegalStateException();
			}
		}
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.lock.distributed.LockCommandContext#getDistibutedLock(net.sf.hajdbc.lock.distributed.RemoteLockDescriptor)
	 */
	@Override
	public Lock getDistibutedLock(RemoteLockDescriptor descriptor)
	{
		String id = descriptor.getId();
		if(id==null){
			id="";
		}
		Lock masterLock = this.onlyLock("masterLock-"+ id);
		return new DistributedLock(descriptor, this.getLock(descriptor), masterLock, this.dispatcher);
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.Lifecycle#start()
	 */
	@Override
	public void start() throws Exception
	{
		this.lockManager.start();
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
		this.lockManager.stop();
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.lock.distributed.LockCommandContext#getRemoteLocks(net.sf.hajdbc.distributed.Remote)
	 */
	@Override
	public Map<LockDescriptor, Lock> getRemoteLocks(Remote remote)
	{
		return getLocks(remote.getMember());
	}

	private Map<LockDescriptor, Lock> getLocks(Member member) {
		synchronized (remoteLockDescriptorMap) {
			Map<LockDescriptor, Lock> locks = this.remoteLockDescriptorMap.get(member);
			if(locks==null){
				locks = new ConcurrentHashMap<>();
				this.remoteLockDescriptorMap.put(member, locks);
			}
			return locks;
		}
	}

	@Override
	public Map<Member, Map<LockDescriptor, Lock>> getAllLocks(boolean includeFree) {
		Map<Member, Map<LockDescriptor, Lock>> allLocks = new HashMap<>();
		for (Member member : this.remoteLockDescriptorMap.keySet()) {
			Map<LockDescriptor, Lock> locks = new HashMap<>();
			allLocks.put(member,locks);
			Map<LockDescriptor, Lock> map = this.getLocks(member);
			for (LockDescriptor descriptor : map.keySet()) {
				Lock lock = map.get(descriptor);
				if(lock instanceof Locked){
					if(includeFree||((Locked)lock).isLocked()){
						locks.put(descriptor, lock);
					}
				}
			}
		}
		return Collections.unmodifiableMap(allLocks);
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.distributed.Stateful#writeState(java.io.ObjectOutput)
	 */
	@Override
	public void writeState(ObjectOutput output) throws IOException
	{
		output.writeInt(this.remoteLockDescriptorMap.size());
		
		for (Map.Entry<Member, Map<LockDescriptor, Lock>> entry: this.remoteLockDescriptorMap.entrySet())
		{
			output.writeObject(entry.getKey());
			
			// 恢复锁依靠持久意向重建，不能降格成无代次的旧锁状态传输。
			Set<LockDescriptor> descriptors = new HashSet<>(entry.getValue().keySet());
			descriptors.removeIf(descriptor -> descriptor instanceof RestoreLockDescriptor);
			
			output.writeInt(descriptors.size());
			
			for (LockDescriptor descriptor: descriptors)
			{
				output.writeUTF(descriptor.getId());
				output.writeByte(descriptor.getType().ordinal());
			}
		}
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.distributed.Stateful#readState(java.io.ObjectInput)
	 */
	@Override
	public void readState(ObjectInput input) throws IOException
	{
		// Is this valid?  or should we unlock/clear?
		//assert this.remoteLockDescriptorMap.isEmpty();
		
		int size = input.readInt();
		
		LockType[] types = LockType.values();
		
		for (int i = 0; i < size; ++i)
		{
			Member member = Objects.readObject(input);
			//Map<LockDescriptor, Lock> map = new HashMap<LockDescriptor, Lock>();
			
			int locks = input.readInt();
			
			for (int j = 0; j < locks; ++j)
			{
				String id = input.readUTF();
				LockType type = types[input.readByte()];
				
				LockDescriptor descriptor = new RemoteLockDescriptorImpl(id, type, member);
				
				Lock lock = this.getLock(descriptor);
				
				lock.lock();
				
				//map.put(descriptor, lock);
			}
			
			//this.remoteLockDescriptorMap.put(member, map);
		}
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.distributed.MembershipListener#added(net.sf.hajdbc.distributed.Member)
	 */
	@Override
	public void added(Member member)
	{
		this.remoteLockDescriptorMap.putIfAbsent(member, new HashMap<LockDescriptor, Lock>());
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.distributed.MembershipListener#removed(net.sf.hajdbc.distributed.Member)
	 */
	@Override
	public void removed(Member member)
	{
		Map<LockDescriptor, Lock> locks = this.remoteLockDescriptorMap.remove(member);

		if (locks != null)
		{
				for (Map.Entry<LockDescriptor, Lock> entry: locks.entrySet())
			{
					if (entry.getKey() instanceof RestoreLockDescriptor) {
						if (!requestRestoreRelease((RestoreLockDescriptor) entry.getKey()))
							getLocks(member).putIfAbsent(entry.getKey(), entry.getValue());
					} else {
						entry.getValue().unlock();
					}
			}

		}
	}


	private static class RemoteLockDescriptorImpl implements RemoteLockDescriptor
	{
		private static final long serialVersionUID = 1950781245453120790L;
		
		private final String id;
		private transient LockType type;
		private final Member member;
		
		RemoteLockDescriptorImpl(String id, LockType type, Member member)
		{
			this.id = id;
			this.type = type;
			this.member = member;
		}
		
		@Override
		public String getId()
		{
			return this.id;
		}

		@Override
		public LockType getType()
		{
			return this.type;
		}

		@Override
		public Member getMember()
		{
			return this.member;
		}

		private void writeObject(ObjectOutputStream out) throws IOException
		{
			out.defaultWriteObject();
			
			out.writeByte(this.type.ordinal());
		}
		
		private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException
		{
			in.defaultReadObject();
			
			this.type = LockType.values()[in.readByte()];
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			RemoteLockDescriptorImpl that = (RemoteLockDescriptorImpl) o;
			return java.util.Objects.equals(id, that.id) && type == that.type && java.util.Objects.equals(member, that.member);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(id, type, member);
		}

		/**
		 * {@inheritDoc}
		 * @see java.lang.Object#toString()
		 */
		@Override
		public String toString()
		{
			StringBuilder builder = new StringBuilder();
			builder.append(this.type.name().toLowerCase())
					.append("Lock(")
					.append((this.id != null) ? this.id : "")
					.append(")[")
					.append(member.toString())
					.append("]");
			return builder.toString();
		}
	}
}
