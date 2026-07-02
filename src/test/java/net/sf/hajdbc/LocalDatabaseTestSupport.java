package net.sf.hajdbc;

import java.util.Set;

import net.sf.hajdbc.util.LocalHost;

/**
 * Test helper for clusters whose startup logic needs one database to match the
 * current host address.
 */
public final class LocalDatabaseTestSupport
{
	private static final String REMOTE_TEST_IP = "192.0.2.254";

	private LocalDatabaseTestSupport()
	{
	}

	public static String localIp()
	{
		Set<String> ips = LocalHost.getAllIp();
		if (ips.isEmpty())
		{
			throw new IllegalStateException("No local IP address found for HA-JDBC cluster tests");
		}
		return ips.iterator().next();
	}

	public static String remoteIp()
	{
		return REMOTE_TEST_IP;
	}
}
