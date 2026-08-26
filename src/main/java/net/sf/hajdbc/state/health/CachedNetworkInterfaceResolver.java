package net.sf.hajdbc.state.health;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 缓存健康检查实际使用的 IP 与网卡映射，避免每个调度周期重复枚举本机地址。
 *
 * <p>成功映射会低频复核；解析失败或网卡检查异常会短暂限流。该类不创建线程，
 * 也不保存系统中的完整地址列表。</p>
 */
final class CachedNetworkInterfaceResolver
{
	static final long SUCCESS_CACHE_NANOS = TimeUnit.SECONDS.toNanos(60);
	static final long FAILURE_CACHE_NANOS = TimeUnit.SECONDS.toNanos(5);

	interface Resolver
	{
		NetworkInterface resolve(String ip) throws Exception;
	}

	interface NanoTimeSource
	{
		long nanoTime();
	}

	private final Resolver resolver;
	private final NanoTimeSource timeSource;
	private final Map<String, Entry> entries = new HashMap<String, Entry>();

	CachedNetworkInterfaceResolver()
	{
		this(new Resolver()
		{
			@Override
			public NetworkInterface resolve(String ip) throws Exception
			{
				return NetworkInterface.getByInetAddress(InetAddress.getByName(ip));
			}
		}, new NanoTimeSource()
		{
			@Override
			public long nanoTime()
			{
				return System.nanoTime();
			}
		});
	}

	CachedNetworkInterfaceResolver(Resolver resolver, NanoTimeSource timeSource)
	{
		this.resolver = resolver;
		this.timeSource = timeSource;
	}

	/**
	 * 返回 IP 对应网卡。缓存过期前不会再次调用系统解析。
	 */
	synchronized NetworkInterface resolve(String ip) throws Exception
	{
		long now = this.timeSource.nanoTime();
		Entry entry = this.entries.get(ip);
		if ((entry != null) && (now < entry.expiresAtNanos))
		{
			return entry.networkInterface;
		}

		try
		{
			NetworkInterface networkInterface = this.resolver.resolve(ip);
			long cacheNanos = (networkInterface != null) ? SUCCESS_CACHE_NANOS : FAILURE_CACHE_NANOS;
			this.entries.put(ip, new Entry(networkInterface, now + cacheNanos));
			return networkInterface;
		}
		catch (Exception e)
		{
			this.entries.put(ip, new Entry(null, now + FAILURE_CACHE_NANOS));
			throw e;
		}
	}

	/**
	 * 网卡对象检查异常后，限制下一次系统解析的最短间隔。
	 */
	synchronized void recordCheckFailure(String ip)
	{
		Entry current = this.entries.get(ip);
		NetworkInterface networkInterface = (current != null) ? current.networkInterface : null;
		this.entries.put(ip, new Entry(networkInterface, this.timeSource.nanoTime() + FAILURE_CACHE_NANOS));
	}

	/**
	 * IP 配置变化时清除全部少量目标缓存。
	 */
	synchronized void invalidateAll()
	{
		this.entries.clear();
	}

	private static final class Entry
	{
		private final NetworkInterface networkInterface;
		private final long expiresAtNanos;

		private Entry(NetworkInterface networkInterface, long expiresAtNanos)
		{
			this.networkInterface = networkInterface;
			this.expiresAtNanos = expiresAtNanos;
		}
	}
}
