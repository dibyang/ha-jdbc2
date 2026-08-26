package net.sf.hajdbc.state.health;

import java.net.InetAddress;
import java.net.NetworkInterface;

import org.junit.Assert;
import org.junit.Test;

public class CachedNetworkInterfaceResolverTest
{
	@Test
	public void repeatedChecksUseOneResolutionUntilSuccessCacheExpires() throws Exception
	{
		MutableClock clock = new MutableClock();
		CountingResolver resolver = new CountingResolver(loopbackInterface());
		CachedNetworkInterfaceResolver cache = new CachedNetworkInterfaceResolver(resolver, clock);

		for (int i = 0; i < 10000; ++i)
		{
			Assert.assertNotNull(cache.resolve("192.0.2.10"));
		}
		Assert.assertEquals(1, resolver.calls);

		clock.nanos = CachedNetworkInterfaceResolver.SUCCESS_CACHE_NANOS;
		cache.resolve("192.0.2.10");
		Assert.assertEquals(2, resolver.calls);
	}

	@Test
	public void missingInterfaceIsRateLimited() throws Exception
	{
		MutableClock clock = new MutableClock();
		CountingResolver resolver = new CountingResolver((NetworkInterface) null);
		CachedNetworkInterfaceResolver cache = new CachedNetworkInterfaceResolver(resolver, clock);

		Assert.assertNull(cache.resolve("192.0.2.11"));
		clock.nanos = CachedNetworkInterfaceResolver.FAILURE_CACHE_NANOS - 1;
		Assert.assertNull(cache.resolve("192.0.2.11"));
		Assert.assertEquals(1, resolver.calls);

		clock.nanos++;
		Assert.assertNull(cache.resolve("192.0.2.11"));
		Assert.assertEquals(2, resolver.calls);
	}

	@Test
	public void resolutionExceptionIsRateLimited() throws Exception
	{
		MutableClock clock = new MutableClock();
		CountingResolver resolver = new CountingResolver(new IllegalStateException("resolve failed"));
		CachedNetworkInterfaceResolver cache = new CachedNetworkInterfaceResolver(resolver, clock);

		try
		{
			cache.resolve("192.0.2.12");
			Assert.fail("Expected resolution failure");
		}
		catch (IllegalStateException e)
		{
			Assert.assertEquals("resolve failed", e.getMessage());
		}
		Assert.assertNull(cache.resolve("192.0.2.12"));
		Assert.assertEquals(1, resolver.calls);
	}

	@Test
	public void checkFailureAndConfigurationInvalidationHaveDifferentTiming() throws Exception
	{
		MutableClock clock = new MutableClock();
		CountingResolver resolver = new CountingResolver(loopbackInterface());
		CachedNetworkInterfaceResolver cache = new CachedNetworkInterfaceResolver(resolver, clock);

		cache.resolve("192.0.2.13");
		cache.recordCheckFailure("192.0.2.13");
		Assert.assertNotNull(cache.resolve("192.0.2.13"));
		Assert.assertEquals(1, resolver.calls);

		clock.nanos = CachedNetworkInterfaceResolver.FAILURE_CACHE_NANOS;
		Assert.assertNotNull(cache.resolve("192.0.2.13"));
		Assert.assertEquals(2, resolver.calls);

		cache.invalidateAll();
		Assert.assertNotNull(cache.resolve("192.0.2.13"));
		Assert.assertEquals(3, resolver.calls);
	}

	private static NetworkInterface loopbackInterface() throws Exception
	{
		NetworkInterface networkInterface = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
		Assert.assertNotNull(networkInterface);
		return networkInterface;
	}

	private static final class MutableClock implements CachedNetworkInterfaceResolver.NanoTimeSource
	{
		private long nanos;

		@Override
		public long nanoTime()
		{
			return this.nanos;
		}
	}

	private static final class CountingResolver implements CachedNetworkInterfaceResolver.Resolver
	{
		private final NetworkInterface networkInterface;
		private final RuntimeException failure;
		private int calls;

		private CountingResolver(NetworkInterface networkInterface)
		{
			this.networkInterface = networkInterface;
			this.failure = null;
		}

		private CountingResolver(RuntimeException failure)
		{
			this.networkInterface = null;
			this.failure = failure;
		}

		@Override
		public NetworkInterface resolve(String ip)
		{
			++this.calls;
			if (this.failure != null)
			{
				throw this.failure;
			}
			return this.networkInterface;
		}
	}
}
