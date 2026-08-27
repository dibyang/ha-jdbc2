package net.sf.hajdbc.state.health;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 缓存一次批量网卡枚举生成的不可变地址快照，避免按目标 IP 调用 native 查询。
 * 该类不创建线程，也不对外暴露完整地址列表。
 */
final class CachedNetworkInterfaceResolver
{
	static final long SUCCESS_CACHE_NANOS = TimeUnit.SECONDS.toNanos(60);
	static final long FAILURE_CACHE_NANOS = TimeUnit.SECONDS.toNanos(5);

	interface SnapshotProvider
	{
		Collection<InterfaceAddresses> load() throws Exception;
	}

	interface NanoTimeSource
	{
		long nanoTime();
	}

	static final class InterfaceAddresses
	{
		private final NetworkInterface networkInterface;
		private final Collection<String> addresses;

		InterfaceAddresses(NetworkInterface networkInterface, Collection<String> addresses)
		{
			this.networkInterface = networkInterface;
			this.addresses = addresses;
		}
	}

	private final SnapshotProvider provider;
	private final NanoTimeSource timeSource;
	private Snapshot snapshot;

	CachedNetworkInterfaceResolver()
	{
		this(new SnapshotProvider()
		{
			@Override
			public Collection<InterfaceAddresses> load() throws Exception
			{
				List<InterfaceAddresses> result = new ArrayList<InterfaceAddresses>();
				Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
				if (interfaces == null)
				{
					return result;
				}
				while (interfaces.hasMoreElements())
				{
					NetworkInterface networkInterface = interfaces.nextElement();
					List<String> addresses = new ArrayList<String>();
					Enumeration<InetAddress> interfaceAddresses = networkInterface.getInetAddresses();
					while (interfaceAddresses.hasMoreElements())
					{
						addresses.add(interfaceAddresses.nextElement().getHostAddress());
					}
					result.add(new InterfaceAddresses(networkInterface, addresses));
				}
				return result;
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

	CachedNetworkInterfaceResolver(SnapshotProvider provider, NanoTimeSource timeSource)
	{
		this.provider = provider;
		this.timeSource = timeSource;
	}

	/**
	 * 返回 IP 对应网卡。一次刷新只枚举一遍所有网卡，后续目标共享同一快照。
	 */
	synchronized NetworkInterface resolve(String ip) throws Exception
	{
		long now = this.timeSource.nanoTime();
		Snapshot current = this.snapshot;
		if ((current == null) || !current.isValid(now))
		{
			current = this.refresh(now);
		}

		NetworkInterface networkInterface = current.interfacesByAddress.get(normalize(ip));
		if ((networkInterface == null) && ((current.expiresAtNanos - now) > FAILURE_CACHE_NANOS))
		{
			this.snapshot = current.withExpiry(now + FAILURE_CACHE_NANOS);
		}
		return networkInterface;
	}

	/**
	 * 网卡对象检查异常后，限制下一次批量枚举的最短间隔。
	 */
	synchronized void recordCheckFailure(String ip)
	{
		Snapshot current = this.snapshot;
		if (current != null)
		{
			long expiresAt = this.timeSource.nanoTime() + FAILURE_CACHE_NANOS;
			if (current.expiresAtNanos > expiresAt)
			{
				this.snapshot = current.withExpiry(expiresAt);
			}
		}
	}

	/**
	 * IP 配置变化时丢弃整个小型枚举快照。
	 */
	synchronized void invalidateAll()
	{
		this.snapshot = null;
	}

	private Snapshot refresh(long now) throws Exception
	{
		try
		{
			Map<String, NetworkInterface> interfacesByAddress = new HashMap<String, NetworkInterface>();
			for (InterfaceAddresses item: this.provider.load())
			{
				for (String address: item.addresses)
				{
					String normalized = normalize(address);
					if (normalized != null)
					{
						interfacesByAddress.put(normalized, item.networkInterface);
					}
				}
			}
			Snapshot result = new Snapshot(Collections.unmodifiableMap(interfacesByAddress), now + SUCCESS_CACHE_NANOS);
			this.snapshot = result;
			return result;
		}
		catch (Exception e)
		{
			this.snapshot = new Snapshot(Collections.<String, NetworkInterface>emptyMap(), now + FAILURE_CACHE_NANOS);
			throw e;
		}
	}

	private static String normalize(String value)
	{
		if ((value == null) || value.isEmpty()) return null;

		String ipv4 = normalizeIpv4(value);
		if (ipv4 != null) return ipv4;

		if (value.indexOf(':') < 0) return null;
		try
		{
			String normalized = InetAddress.getByName(value).getHostAddress().toLowerCase(Locale.ENGLISH);
			int scope = normalized.indexOf('%');
			return (scope >= 0) ? normalized.substring(0, scope) : normalized;
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private static String normalizeIpv4(String value)
	{
		String[] parts = value.split("\\.", -1);
		if (parts.length != 4) return null;
		StringBuilder builder = new StringBuilder(value.length());
		for (int i = 0; i < parts.length; ++i)
		{
			String part = parts[i];
			if (part.isEmpty() || (part.length() > 3)) return null;
			int number = 0;
			for (int j = 0; j < part.length(); ++j)
			{
				char character = part.charAt(j);
				if ((character < '0') || (character > '9')) return null;
				number = (number * 10) + (character - '0');
			}
			if (number > 255) return null;
			if (i > 0) builder.append('.');
			builder.append(number);
		}
		return builder.toString();
	}

	private static final class Snapshot
	{
		private final Map<String, NetworkInterface> interfacesByAddress;
		private final long expiresAtNanos;

		private Snapshot(Map<String, NetworkInterface> interfacesByAddress, long expiresAtNanos)
		{
			this.interfacesByAddress = interfacesByAddress;
			this.expiresAtNanos = expiresAtNanos;
		}

		private boolean isValid(long now)
		{
			return (now - this.expiresAtNanos) < 0L;
		}

		private Snapshot withExpiry(long expiresAtNanos)
		{
			return new Snapshot(this.interfacesByAddress, expiresAtNanos);
		}
	}
}
