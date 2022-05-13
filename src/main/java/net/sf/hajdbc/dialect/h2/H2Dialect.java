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
package net.sf.hajdbc.dialect.h2;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;

import net.sf.hajdbc.*;
import net.sf.hajdbc.codec.Decoder;
import net.sf.hajdbc.dialect.ConnectionProperties;
import net.sf.hajdbc.dialect.StandardDialect;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.sql.DatabaseClusterImpl;
import net.sf.hajdbc.util.Resources;
import org.h2.tools.Restore;
import org.h2.tools.RunScript;
import org.h2.tools.Script;

/**
 * Dialect for <a href="http://www.h2database.com">H2 Database Engine</a>.
 * @author Paul Ferraro
 */
public class H2Dialect extends StandardDialect implements DumpRestoreSupport
{
	static final Logger logger = LoggerFactory.getLogger(DatabaseClusterImpl.class);
	private static final Set<Integer> failureCodes = new HashSet<Integer>(Arrays.asList(90013, 90030, 90046, 90067, 90100, 90108, 90117, 90121));
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.dialect.StandardDialect#vendorPattern()
	 */
	@Override
	protected String vendorPattern()
	{
		return "h2";
	}

	/**
	 * @see net.sf.hajdbc.dialect.StandardDialect#executeFunctionFormat()
	 */
	@Override
	protected String executeFunctionFormat()
	{
		return "CALL {0}";
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.dialect.StandardDialect#getSequenceSupport()
	 */
	@Override
	public SequenceSupport getSequenceSupport()
	{
		return this;
	}

	@Override
	public Collection<SequenceProperties> getSequences(DatabaseMetaData metaData, SequencePropertiesFactory factory) throws SQLException
	{
		Statement statement = metaData.getConnection().createStatement();
		
		try
		{
			ResultSet resultSet = statement.executeQuery("SELECT SEQUENCE_SCHEMA, SEQUENCE_NAME, INCREMENT FROM INFORMATION_SCHEMA.SEQUENCES");
			
			List<SequenceProperties> sequences = new LinkedList<SequenceProperties>();
			
			while (resultSet.next())
			{
				sequences.add(factory.createSequenceProperties(resultSet.getString(1), resultSet.getString(2), resultSet.getInt(3)));
			}
			
			return sequences;
		}
		finally
		{
			Resources.close(statement);
		}
	}

	/**
	 * Deferrability clause is not supported.
	 * @see net.sf.hajdbc.dialect.StandardDialect#createForeignKeyConstraintFormat()
	 */
	@Override
	protected String createForeignKeyConstraintFormat()
	{
		return "ALTER TABLE {1} ADD CONSTRAINT {0} FOREIGN KEY ({2}) REFERENCES {3} ({4}) ON DELETE {5,choice,0#CASCADE|1#RESTRICT|2#SET NULL|3#NO ACTION|4#SET DEFAULT} ON UPDATE {6,choice,0#CASCADE|1#RESTRICT|2#SET NULL|3#NO ACTION|4#SET DEFAULT}";
	}

	/**
	 * @see net.sf.hajdbc.dialect.StandardDialect#currentDatePattern()
	 */
	@Override
	protected String currentDatePattern()
	{
		return "(?<=\\W)CURRENT_DATE(?:\\s*\\(\\s*\\))?(?=\\W)|(?<=\\W)CURDATE\\s*\\(\\s*\\)|(?<=\\W)SYSDATE(?=\\W)|(?<=\\W)TODAY(?=\\W)";
	}

	/**
	 * @see net.sf.hajdbc.dialect.StandardDialect#currentTimePattern()
	 */
	@Override
	protected String currentTimePattern()
	{
		return "(?<=\\W)CURRENT_TIME(?:\\s*\\(\\s*\\))?(?=\\W)|(?<=\\W)CURTIME\\s*\\(\\s*\\)";
	}

	/**
	 * @see net.sf.hajdbc.dialect.StandardDialect#currentTimestampPattern()
	 */
	@Override
	protected String currentTimestampPattern()
	{
		return "(?<=\\W)CURRENT_TIMESTAMP(?:\\s*\\(\\s*\\d*\\s*\\))?(?=\\W)|(?<=\\W)NOW\\s*\\(\\s*\\d*\\s*\\)";
	}

	/**
	 * @see net.sf.hajdbc.dialect.StandardDialect#randomPattern()
	 */
	@Override
	protected String randomPattern()
	{
		return "(?<=\\W)RAND\\s*\\(\\s*\\d*\\s*\\)";
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.dialect.StandardDialect#getDefaultSchemas(java.sql.DatabaseMetaData)
	 */
	@Override
	public List<String> getDefaultSchemas(DatabaseMetaData metaData)
	{
		return Collections.singletonList("PUBLIC");
	}

	@Override
	protected boolean indicatesFailure(int code)
	{
		return failureCodes.contains(code);
	}

	@Override
	public DumpRestoreSupport getDumpRestoreSupport()
	{
		return this;
	}

	@Override
	public <Z, D extends Database<Z>> void dump(D database, Decoder decoder, File file, boolean dataOnly) throws Exception {
		final String password = database.decodePassword(decoder);
		Properties properties = this.getDatabaseProperties(database, password);
		String url = properties.getProperty("url");
		String userName = properties.getProperty("userName");
		logger.log(Level.INFO,"h2 dump url={0} path={1}",url,file.getPath());
		Script.main("-url", url,"-user",userName,"-password",password,"-script", file.getPath());
	}

	@Override
	public <Z, D extends Database<Z>> void restore(D database, Decoder decoder, File file, boolean dataOnly) throws Exception {
		final String password = database.decodePassword(decoder);
		Properties properties = this.getDatabaseProperties(database, password);
		String url = properties.getProperty("url");
		String userName = properties.getProperty("userName");
		logger.log(Level.INFO,"h2 restore url={0} path={1}",url,file.getPath());
		Path target = Paths.get("/root/h2/", file.getName());
		if(!target.toFile().getParentFile().exists()){
			target.toFile().getParentFile().mkdirs();
		}
		Files.copy(file.toPath(), target);
		RunScript.main("-url", url,"-user",userName,"-password",password,"-script", file.getPath());
	}
}
