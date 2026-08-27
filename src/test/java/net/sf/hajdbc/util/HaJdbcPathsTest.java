package net.sf.hajdbc.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class HaJdbcPathsTest
{
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	private final String originalConfigDir = System.getProperty(HaJdbcPaths.CONFIG_DIR_PROPERTY);
	private final String originalTraceDir = System.getProperty(HaJdbcPaths.TRACE_DIR_PROPERTY);
	private final String originalNetTcpFile = System.getProperty(HaJdbcPaths.NET_TCP_FILE_PROPERTY);

	@After
	public void restoreProperties()
	{
		restore(HaJdbcPaths.CONFIG_DIR_PROPERTY, this.originalConfigDir);
		restore(HaJdbcPaths.TRACE_DIR_PROPERTY, this.originalTraceDir);
		restore(HaJdbcPaths.NET_TCP_FILE_PROPERTY, this.originalNetTcpFile);
	}

	@Test
	public void fileReaderUsesConfiguredConfigDirectory() throws Exception
	{
		System.setProperty(HaJdbcPaths.CONFIG_DIR_PROPERTY, this.temporaryFolder.getRoot().getAbsolutePath());
		Files.write(this.temporaryFolder.newFile("max_unobservable").toPath(), "9".getBytes(StandardCharsets.UTF_8));

		FileReader<Integer> reader = FileReader.of4int("max_unobservable");

		Assert.assertEquals(Integer.valueOf(9), reader.getData(1));
	}

	@Test
	public void traceDirectoryCanBeConfiguredIndependently()
	{
		System.setProperty(HaJdbcPaths.CONFIG_DIR_PROPERTY, this.temporaryFolder.getRoot().getAbsolutePath());
		System.setProperty(HaJdbcPaths.TRACE_DIR_PROPERTY, this.temporaryFolder.getRoot().toPath().resolve("custom-trace").toString());

		Assert.assertEquals(this.temporaryFolder.getRoot().toPath().resolve("custom-trace").resolve("health"),
			HaJdbcPaths.traceFile("health"));
	}

	@Test
	public void netTcpFileCanBeConfigured()
	{
		System.setProperty(HaJdbcPaths.NET_TCP_FILE_PROPERTY,
			this.temporaryFolder.getRoot().toPath().resolve("tcp").toString());

		Assert.assertEquals(this.temporaryFolder.getRoot().toPath().resolve("tcp"),
			HaJdbcPaths.netTcpFile());
	}

	private static void restore(String property, String value)
	{
		if (value == null)
		{
			System.clearProperty(property);
		}
		else
		{
			System.setProperty(property, value);
		}
	}
}
