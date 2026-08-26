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
package net.sf.hajdbc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class ProcessesTest
{
	private static final String SENTINEL = "S3ntinel 空 格!#\\尾";
	private static final String DASH_SENTINEL = "-S3ntinel-空格-尾";
	private static final String ENVIRONMENT_SENTINEL = "Environment-Only-Secret";

	@Test
	public void redactsPasswordTokensWithoutChangingOriginalCommand()
	{
		List<String> command = new ArrayList<String>(Arrays.asList(
			"mysql",
			"--password=" + SENTINEL,
			"--PASSWORD=SecondSecret",
			"--PaSsWoRd",
			SENTINEL,
			"-pshortSecret",
			"--no-password",
			"-P3306",
			"--password-file=client.cnf",
			"ordinary=" + SENTINEL));
		List<String> original = new ArrayList<String>(command);

		List<String> redacted = Processes.redactCommand(command);

		assertEquals(Arrays.asList(
			"mysql",
			"--password=[REDACTED]",
			"--PASSWORD=[REDACTED]",
			"--PaSsWoRd",
			"[REDACTED]",
			"-p[REDACTED]",
			"--no-password",
			"-P3306",
			"--password-file=client.cnf",
			"ordinary=" + SENTINEL), redacted);
		assertEquals(original, command);
	}

	@Test
	public void missingAndEmptyPasswordValuesDoNotDamageFollowingOptions()
	{
		List<String> command = Arrays.asList(
			"mysql",
			"--no-password",
			"-P3306",
			"--password",
			"",
			"--password=",
			"-p",
			"--password");

		assertEquals(Arrays.asList(
			"mysql",
			"--no-password",
			"-P3306",
			"--password",
			"[REDACTED]",
			"--password=[REDACTED]",
			"-p",
			"--password"), Processes.redactCommand(command));
	}

	@Test
	public void dashPrefixedPasswordIsRedactedFromCopiesAndFailureDiagnostics() throws Exception
	{
		ProcessBuilder builder = new ProcessBuilder("mysql", "--password", DASH_SENTINEL, "--no-password", "-P3306");
		List<String> original = new ArrayList<String>(builder.command());

		assertEquals(Arrays.asList("mysql", "--password", "[REDACTED]", "--no-password", "-P3306"), Processes.redactCommand(builder.command()));
		String summary = Processes.commandSummary(builder.command());
		assertTrue(summary.contains("--password [REDACTED]"));
		assertTrue(summary.contains("--no-password"));
		assertTrue(summary.contains("-P3306"));
		assertFalse(summary.contains(DASH_SENTINEL));

		Process process = mock(Process.class);
		when(process.waitFor()).thenReturn(9);
		when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
		Processes.ProcessFactory factory = mock(Processes.ProcessFactory.class);
		when(factory.start(same(builder))).thenReturn(process);

		try
		{
			Processes.run(builder, null, factory);
			fail("Expected non-zero process failure");
		}
		catch (Exception e)
		{
			assertTrue(e.getMessage().contains("--password [REDACTED]"));
			assertTrue(e.getMessage().contains("return-code=9"));
			assertFalse(e.getMessage().contains(DASH_SENTINEL));
		}

		assertEquals(original, builder.command());
	}

	@Test
	public void commandSummaryIsRedactedBoundedAndIgnoresEnvironment()
	{
		char[] padding = new char[2000];
		Arrays.fill(padding, 'x');
		ProcessBuilder builder = new ProcessBuilder("mysql", "--password=" + SENTINEL, new String(padding));
		builder.environment().put("MYSQL_PWD", ENVIRONMENT_SENTINEL);

		String summary = Processes.commandSummary(builder.command());

		assertTrue(summary.contains("mysql"));
		assertTrue(summary.contains("--password=[REDACTED]"));
		assertTrue(summary.endsWith("..."));
		assertTrue(summary.length() <= 1024);
		assertFalse(summary.contains(SENTINEL));
		assertFalse(summary.contains(ENVIRONMENT_SENTINEL));
	}

	@Test
	public void nonZeroFailureContainsOnlyRedactedDiagnostics() throws Exception
	{
		ProcessBuilder builder = new ProcessBuilder("mysql", "--password", SENTINEL, "--execute=select 1");
		builder.environment().put("MYSQL_PWD", ENVIRONMENT_SENTINEL);
		List<String> original = new ArrayList<String>(builder.command());
		Process process = mock(Process.class);
		when(process.waitFor()).thenReturn(7);
		when(process.getInputStream()).thenReturn(new ByteArrayInputStream(("child:" + SENTINEL).getBytes(StandardCharsets.UTF_8)));
		Processes.ProcessFactory factory = mock(Processes.ProcessFactory.class);
		when(factory.start(same(builder))).thenReturn(process);

		try
		{
			Processes.run(builder, null, factory);
			fail("Expected non-zero process failure");
		}
		catch (Exception e)
		{
			assertTrue(e.getMessage().contains("mysql"));
			assertTrue(e.getMessage().contains("--password [REDACTED]"));
			assertTrue(e.getMessage().contains("return-code=7"));
			assertFalse(e.getMessage().contains(SENTINEL));
			assertFalse(e.getMessage().contains(ENVIRONMENT_SENTINEL));
		}

		assertEquals(original, builder.command());
		assertEquals(ENVIRONMENT_SENTINEL, builder.environment().get("MYSQL_PWD"));
	}

	@Test
	public void interruptedFailureUsesRedactedCommandAndRestoresInterrupt() throws Exception
	{
		ProcessBuilder builder = new ProcessBuilder("mysql", "-p" + SENTINEL);
		builder.environment().put("MYSQL_PWD", ENVIRONMENT_SENTINEL);
		Process process = mock(Process.class);
		InterruptedException interruption = new InterruptedException("interrupted");
		when(process.waitFor()).thenThrow(interruption);
		Processes.ProcessFactory factory = mock(Processes.ProcessFactory.class);
		when(factory.start(same(builder))).thenReturn(process);

		try
		{
			Processes.run(builder, null, factory);
			fail("Expected interrupted process failure");
		}
		catch (Exception e)
		{
			assertEquals(interruption, e.getCause());
			assertTrue(e.getMessage().contains("mysql -p[REDACTED]"));
			assertTrue(e.getMessage().contains("interrupted"));
			assertFalse(e.getMessage().contains(SENTINEL));
			assertFalse(e.getMessage().contains(ENVIRONMENT_SENTINEL));
			assertTrue(Thread.currentThread().isInterrupted());
		}
		finally
		{
			Thread.interrupted();
		}
	}
}
