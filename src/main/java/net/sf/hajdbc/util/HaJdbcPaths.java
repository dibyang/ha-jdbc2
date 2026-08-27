package net.sf.hajdbc.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 集中管理运行时文件系统路径，并保留既有部署默认值。
 */
public final class HaJdbcPaths
{
	public static final String CONFIG_DIR_PROPERTY = "ha-jdbc.config-dir";
	public static final String TRACE_DIR_PROPERTY = "ha-jdbc.trace-dir";
	public static final String MOUNTS_FILE_PROPERTY = "ha-jdbc.mounts-file";
	public static final String MANAGER_FS_IP_FILE_PROPERTY = "ha-jdbc.manager-fs-ip-file";
	public static final String NET_TCP_FILE_PROPERTY = "ha-jdbc.net-tcp-file";

	public static final String DEFAULT_CONFIG_DIR = "/etc/ha-jdbc";
	public static final String DEFAULT_MOUNTS_FILE = "/proc/mounts";
	public static final String DEFAULT_MANAGER_FS_IP_FILE = "/etc/aio/.mfs_ip";
	public static final String DEFAULT_NET_TCP_FILE = "/proc/net/tcp";

	private HaJdbcPaths()
	{
	}

	/**
	 * 返回 HA-JDBC 配置目录。
	 */
	public static Path configDir()
	{
		return path(CONFIG_DIR_PROPERTY, DEFAULT_CONFIG_DIR);
	}

	/**
	 * 返回 HA-JDBC 配置目录下的指定文件。
	 */
	public static Path configFile(String name)
	{
		return configDir().resolve(name);
	}

	/**
	 * 返回诊断 trace 标记文件目录。
	 */
	public static Path traceDir()
	{
		String traceDir = System.getProperty(TRACE_DIR_PROPERTY);
		return (traceDir != null && !traceDir.trim().isEmpty()) ? Paths.get(traceDir) : configDir().resolve("trace");
	}

	/**
	 * 返回指定诊断 trace 标记文件。
	 */
	public static Path traceFile(String name)
	{
		return traceDir().resolve(name);
	}

	/**
	 * 返回系统挂载信息文件。
	 */
	public static Path mountsFile()
	{
		return path(MOUNTS_FILE_PROPERTY, DEFAULT_MOUNTS_FILE);
	}

	/**
	 * 返回管理文件系统 IP 列表文件。
	 */
	public static Path managerFsIpFile()
	{
		return path(MANAGER_FS_IP_FILE_PROPERTY, DEFAULT_MANAGER_FS_IP_FILE);
	}

	/**
	 * 返回网络探测读取的 TCP 链路信息文件。
	 */
	public static Path netTcpFile()
	{
		return path(NET_TCP_FILE_PROPERTY, DEFAULT_NET_TCP_FILE);
	}

	private static Path path(String property, String defaultValue)
	{
		String value = System.getProperty(property);
		return Paths.get((value != null && !value.trim().isEmpty()) ? value : defaultValue);
	}
}
