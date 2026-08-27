package net.sf.hajdbc.state.health;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

public class CachedNetworkInterfaceResolverTest
{
	@Test
	public void thousandsOfTargetsShareOneEnumeration() throws Exception
	{
		MutableClock clock = new MutableClock();
		NetworkInterface networkInterface = loopbackInterface();
		List<String> addresses = new ArrayList<String>(5000);
		for (int i = 0; i < 5000; ++i)
		{
			addresses.add("10." + ((i >>> 16) & 0xff) + "." + ((i >>> 8) & 0xff) + "." + (i & 0xff));
		}
		CountingProvider provider = new CountingProvider(new CachedNetworkInterfaceResolver.InterfaceAddresses(networkInterface, addresses));
		CachedNetworkInterfaceResolver cache = new CachedNetworkInterfaceResolver(provider, clock);

		for (String address: addresses)
		{
			Assert.assertSame(networkInterface, cache.resolve(address));
		}
		Assert.assertEquals(1, provider.calls);
	}

	@Test
	public void repeatedChecksUseOneEnumerationUntilSuccessCacheExpires() throws Exception
	{
		MutableClock clock = new MutableClock();
		NetworkInterface networkInterface = loopbackInterface();
		CountingProvider provider = provider(networkInterface, "192.0.2.10");
		CachedNetworkInterfaceResolver cache = new CachedNetworkInterfaceResolver(provider, clock);

		for (int i = 0; i < 10000; ++i)
		{
			Assert.assertSame(networkInterface, cache.resolve("192.0.2.10"));
		}
		Assert.assertEquals(1, provider.calls);

		clock.nanos = CachedNetworkInterfaceResolver.SUCCESS_CACHE_NANOS;
		cache.resolve("192.0.2.10");
		Assert.assertEquals(2, provider.calls);
	}

	@Test
	public void missingInterfaceShortensSnapshotToFailureTtl() throws Exception
	{
		MutableClock clock = new MutableClock();
		CountingProvider provider = provider(loopbackInterface(), "192.0.2.10");
		CachedNetworkInterfaceResolver cache = new CachedNetworkInterfaceResolver(provider, clock);

		Assert.assertNull(cache.resolve("192.0.2.11"));
		clock.nanos = CachedNetworkInterfaceResolver.FAILURE_CACHE_NANOS - 1L;
		Assert.assertNull(cache.resolve("192.0.2.11"));
		Assert.assertEquals(1, provider.calls);

		clock.nanos++;
		Assert.assertNull(cache.resolve("192.0.2.11"));
		Assert.assertEquals(2, provider.calls);
	}

	@Test
	public void enumerationExceptionIsRateLimited() throws Exception
	{
		MutableClock clock = new MutableClock();
		CountingProvider provider = new CountingProvider(new IllegalStateException("enumeration failed"));
		CachedNetworkInterfaceResolver cache = new CachedNetworkInterfaceResolver(provider, clock);

		try
		{
			cache.resolve("192.0.2.12");
			Assert.fail("Expected enumeration failure");
		}
		catch (IllegalStateException e)
		{
			Assert.assertEquals("enumeration failed", e.getMessage());
		}
		Assert.assertNull(cache.resolve("192.0.2.12"));
		Assert.assertEquals(1, provider.calls);
	}

	@Test
	public void checkFailureAndConfigurationInvalidationHaveDifferentTiming() throws Exception
	{
		MutableClock clock = new MutableClock();
		NetworkInterface networkInterface = loopbackInterface();
		CountingProvider provider = provider(networkInterface, "192.0.2.13");
		CachedNetworkInterfaceResolver cache = new CachedNetworkInterfaceResolver(provider, clock);

		cache.resolve("192.0.2.13");
		cache.recordCheckFailure("192.0.2.13");
		Assert.assertSame(networkInterface, cache.resolve("192.0.2.13"));
		Assert.assertEquals(1, provider.calls);

		clock.nanos = CachedNetworkInterfaceResolver.FAILURE_CACHE_NANOS;
		Assert.assertSame(networkInterface, cache.resolve("192.0.2.13"));
		Assert.assertEquals(2, provider.calls);

		cache.invalidateAll();
		Assert.assertSame(networkInterface, cache.resolve("192.0.2.13"));
		Assert.assertEquals(3, provider.calls);
	}

	@Test
	public void concurrentFirstTargetsPerformOneEnumeration() throws Exception
	{
		final int threads = 32;
		MutableClock clock = new MutableClock();
		NetworkInterface networkInterface = loopbackInterface();
		BlockingProvider provider = new BlockingProvider(new CachedNetworkInterfaceResolver.InterfaceAddresses(networkInterface, Collections.singletonList("192.0.2.20")));
		final CachedNetworkInterfaceResolver cache = new CachedNetworkInterfaceResolver(provider, clock);
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		List<Future<NetworkInterface>> futures = new ArrayList<Future<NetworkInterface>>(threads);
		try
		{
			for (int i = 0; i < threads; ++i)
			{
				futures.add(executor.submit(() -> cache.resolve("192.0.2.20")));
			}
			Assert.assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
			provider.release.countDown();
			for (Future<NetworkInterface> future: futures)
			{
				Assert.assertSame(networkInterface, future.get(5, TimeUnit.SECONDS));
			}
			Assert.assertEquals(1, provider.calls);
		}
		finally
		{
			provider.release.countDown();
			executor.shutdownNow();
			Assert.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void numericAddressesAreCanonicalizedWithoutHostNameLookup() throws Exception
	{
		NetworkInterface networkInterface = loopbackInterface();
		CountingProvider provider = provider(networkInterface, "192.0.2.1", "0:0:0:0:0:0:0:1");
		CachedNetworkInterfaceResolver cache = new CachedNetworkInterfaceResolver(provider, new MutableClock());

		Assert.assertSame(networkInterface, cache.resolve("192.000.002.001"));
		Assert.assertSame(networkInterface, cache.resolve("::1"));
		Assert.assertNull(cache.resolve("localhost"));
		Assert.assertEquals(1, provider.calls);
	}

	private static CountingProvider provider(NetworkInterface networkInterface, String... addresses)
	{
		return new CountingProvider(new CachedNetworkInterfaceResolver.InterfaceAddresses(networkInterface, java.util.Arrays.asList(addresses)));
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

	private static class CountingProvider implements CachedNetworkInterfaceResolver.SnapshotProvider
	{
		private final Collection<CachedNetworkInterfaceResolver.InterfaceAddresses> interfaces;
		private final RuntimeException failure;
		int calls;

		private CountingProvider(CachedNetworkInterfaceResolver.InterfaceAddresses item)
		{
			this.interfaces = Collections.singletonList(item);
			this.failure = null;
		}

		private CountingProvider(RuntimeException failure)
		{
			this.interfaces = Collections.emptyList();
			this.failure = failure;
		}

		@Override
		public Collection<CachedNetworkInterfaceResolver.InterfaceAddresses> load() throws Exception
		{
			++this.calls;
			if (this.failure != null)
			{
				throw this.failure;
			}
			return this.interfaces;
		}
	}

	private static final class BlockingProvider extends CountingProvider
	{
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);

		private BlockingProvider(CachedNetworkInterfaceResolver.InterfaceAddresses item)
		{
			super(item);
		}

		@Override
		public Collection<CachedNetworkInterfaceResolver.InterfaceAddresses> load() throws Exception
		{
			this.entered.countDown();
			Assert.assertTrue(this.release.await(5, TimeUnit.SECONDS));
			return super.load();
		}
	}
}
