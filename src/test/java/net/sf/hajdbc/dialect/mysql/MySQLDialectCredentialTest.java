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
package net.sf.hajdbc.dialect.mysql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import java.util.Locale;

import net.sf.hajdbc.dialect.ConnectionProperties;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MySQLDialectCredentialTest
{
	private static final String SENTINEL = "S3ntinel 空 格!#\\尾";

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private MySQLDialect dialect;
	private ConnectionProperties properties;
	private File dumpFile;
	private File missingPasswordFile;

	@Before
	public void before()
	{
		this.dialect = new MySQLDialect();
		this.properties = mock(ConnectionProperties.class);
		this.dumpFile = new File(this.temporaryFolder.getRoot(), "dump file.sql");
		this.missingPasswordFile = new File(this.temporaryFolder.getRoot(), "missing.cnf");

		when(this.properties.getHost()).thenReturn("db.example");
		when(this.properties.getPort()).thenReturn("3306");
		when(this.properties.getUser()).thenReturn("db-user");
		when(this.properties.getDatabase()).thenReturn("db-name");
		when(this.properties.getPassword()).thenReturn(SENTINEL);
	}

	@Test
	public void dumpOmitsPasswordArgvAndKeepsMysqlPwdCompatibility()
	{
		ProcessBuilder builder = this.dialect.createDumpProcessBuilder(this.properties, this.dumpFile, true, this.missingPasswordFile);

		assertCredentialOutsideArgv(builder);
		assertTrue(builder.command().contains("--no-create-info"));
		assertTrue(builder.command().contains("--skip-triggers"));
		assertTrue(builder.command().contains("--result-file=" + this.dumpFile.getPath()));
	}

	@Test
	public void restoreOmitsPasswordArgvAndKeepsMysqlPwdCompatibility()
	{
		ProcessBuilder builder = this.dialect.createRestoreProcessBuilder(this.properties, this.dumpFile, this.missingPasswordFile);

		assertCredentialOutsideArgv(builder);
		assertTrue(builder.command().contains("--database=db-name"));
		assertTrue(builder.command().contains("source " + this.dumpFile.getPath()));
	}

	@Test
	public void existingPasswordFileAvoidsMysqlPwdWithoutChangingArgv() throws Exception
	{
		File passwordFile = this.temporaryFolder.newFile("client.cnf");

		ProcessBuilder builder = this.dialect.createDumpProcessBuilder(this.properties, this.dumpFile, false, passwordFile);

		assertNoPasswordArgument(builder.command());
		assertFalse(builder.environment().containsKey("MYSQL_PWD"));
		assertFalse(builder.command().contains("--no-create-info"));
		assertFalse(builder.command().contains("--skip-triggers"));
	}

	@Test
	public void nullAndEmptyPasswordsNeverBecomeArguments()
	{
		when(this.properties.getPassword()).thenReturn(null);
		ProcessBuilder nullPassword = this.dialect.createDumpProcessBuilder(this.properties, this.dumpFile, false, this.missingPasswordFile);

		assertNoPasswordArgument(nullPassword.command());
		assertFalse(nullPassword.environment().containsKey("MYSQL_PWD"));

		when(this.properties.getPassword()).thenReturn("");
		ProcessBuilder emptyPassword = this.dialect.createRestoreProcessBuilder(this.properties, this.dumpFile, this.missingPasswordFile);

		assertNoPasswordArgument(emptyPassword.command());
		assertTrue(emptyPassword.environment().containsKey("MYSQL_PWD"));
		assertEquals("", emptyPassword.environment().get("MYSQL_PWD"));
	}

	private static void assertCredentialOutsideArgv(ProcessBuilder builder)
	{
		assertNoPasswordArgument(builder.command());
		assertFalse(builder.command().toString().contains(SENTINEL));
		assertEquals(SENTINEL, builder.environment().get("MYSQL_PWD"));
	}

	private static void assertNoPasswordArgument(List<String> command)
	{
		for (String argument: command)
		{
			assertFalse("Password argument leaked: " + argument, argument.toLowerCase(Locale.ROOT).startsWith("--password"));
			assertFalse("Short password argument leaked: " + argument, argument.startsWith("-p") && !argument.startsWith("--"));
		}
	}
}
