package net.sf.hajdbc.state.health;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.MockDatabase;
import net.sf.hajdbc.balancer.Balancer;
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.distributed.jgroups.AddressMember;
import net.sf.hajdbc.exception.StartFailException;
import net.sf.hajdbc.state.distributed.DistributedStateManager;

import org.jgroups.Address;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jgroups.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClusterHealthImplTest
{
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	private String originalAppRoot;

	@Before
	public void setAppRoot() throws Exception
	{
		this.originalAppRoot = System.getProperty("app.root");
		File root = this.temporaryFolder.newFolder("app-root");
		System.setProperty("app.root", root.getAbsolutePath());
	}

	@After
	public void restoreAppRoot()
	{
		if (this.originalAppRoot == null)
		{
			System.clearProperty("app.root");
		}
		else
		{
			System.setProperty("app.root", this.originalAppRoot);
		}
	}

	@Test
	public void startPropagatesStartupFailureInsteadOfExitingJvm()
	{
		DistributedStateManager stateManager = stateManager();
		ClusterHealthImpl health = new FailingClusterHealth(stateManager);

		try
		{
			health.start();
			Assert.fail("Expected startup failure to be propagated");
		}
		catch (IllegalStateException e)
		{
			Assert.assertEquals(StartFailException.MESSAGE, e.getMessage());
			Assert.assertTrue(e.getCause() instanceof StartFailException);
		}
		finally
		{
			health.stop();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void remoteHostUsesDatabaseIpInsteadOfDatabaseId()
	{
		String localIp = "10.0.0.1";
		String hostIp = "10.0.0.2";
		Address localAddress = address(localIp, 1);
		Address hostAddress = address(hostIp, 2);
		Member local = new AddressMember(localAddress);
		Member host = new AddressMember(hostAddress);
		DistributedStateManager stateManager = stateManager(localIp);
		DatabaseCluster cluster = stateManager.getDatabaseCluster();
		Balancer balancer = mock(Balancer.class);
		Set<String> activeDatabases = new HashSet<String>();
		MockDatabase hostDatabase = new MockDatabase("db-host");
		hostDatabase.setIp(hostIp);

		when(stateManager.getLocal()).thenReturn(local);
		when(stateManager.getActiveDatabases()).thenReturn(activeDatabases);
		when(cluster.getDatabase(hostIp)).thenThrow(new IllegalArgumentException("database id is not ip"));
		when(cluster.getDatabaseByIp(hostIp)).thenReturn(hostDatabase);
		when(cluster.getBalancer()).thenReturn(balancer);
		when(balancer.size()).thenReturn(1);

		try
		{
			ClusterHealthImpl health = new ClusterHealthImpl(stateManager);
			health.host(host, 1L);

			Assert.assertTrue(activeDatabases.contains("db-host"));
			verify(cluster).getDatabaseByIp(hostIp);
			verify(cluster, never()).getDatabase(hostIp);
		}
		finally
		{
			UUID.remove(localAddress);
			UUID.remove(hostAddress);
		}
	}

	private static DistributedStateManager stateManager()
	{
		return stateManager("127.0.0.1");
	}

	private static DistributedStateManager stateManager(String localIp)
	{
		DistributedStateManager stateManager = mock(DistributedStateManager.class);
		DatabaseCluster cluster = mock(DatabaseCluster.class);
		when(stateManager.getDatabaseCluster()).thenReturn(cluster);
		when(stateManager.getLocalIp()).thenReturn(localIp);
		when(cluster.getId()).thenReturn("cluster-health-test");
		when(cluster.getNodes()).thenReturn(Collections.singletonList(localIp));
		return stateManager;
	}

	private static Address address(String ip, long seed)
	{
		Address address = new UUID(0, seed);
		UUID.add(address, ip);
		return address;
	}

	private static class FailingClusterHealth extends ClusterHealthImpl
	{
		FailingClusterHealth(DistributedStateManager stateManager)
		{
			super(stateManager);
		}

		@Override
		protected void runStartupTask() throws StartFailException
		{
			throw new StartFailException("test failure");
		}
	}
}
