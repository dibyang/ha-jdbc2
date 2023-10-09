package net.sf.hajdbc.lock.reentrant;

import net.sf.hajdbc.lock.LockManager;
import net.sf.hajdbc.lock.LockManagerFactory;

public class ReentrantLockManagerFactory implements LockManagerFactory
{
	private static final long serialVersionUID = -3053191628786826662L;

	private boolean fair;
	
	public void setFair(boolean fair)
	{
		this.fair = fair;
	}
	
	public boolean isFair()
	{
		return this.fair;
	}

	@Override
	public String getId()
	{
		return "reentrant";
	}
	
	@Override
	public LockManager createLockManager()
	{
		return new ReentrantLockManager(this.fair);
	}
}
