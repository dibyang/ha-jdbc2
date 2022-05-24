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

import net.sf.hajdbc.*;
import net.sf.hajdbc.codec.Decoder;
import net.sf.hajdbc.dialect.StandardDialect;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.sql.DatabaseClusterImpl;
import net.sf.hajdbc.util.Resources;
import org.h2.engine.Constants;
import org.h2.message.DbException;
import org.h2.store.fs.FileUtils;
import org.h2.tools.RunScript;
import org.h2.tools.Script;
import org.h2.util.IOUtils;
import org.h2.util.ScriptReader;
import org.h2.util.StringUtils;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.*;


/**
 * Dialect for <a href="http://www.h2database.com">H2 Database Engine</a>.
 * @author Paul Ferraro
 */
public class H2Dialect extends StandardDialect
		implements DumpRestoreSupport
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
		Connection connection = database.connect(database.getConnectionSource(), password);
		Charset charset = Charset.forName("utf-8");
		try
		{
			dropAllObjects(connection);
			BufferedReader reader = Files.newBufferedReader(file.toPath());
			try {
				process(connection, true, file.getParent(), reader, charset);
			} finally {
				IOUtils.closeSilently(reader);
			}
		}
		finally
		{
			connection.close();
		}
	}



	void dropAllObjects(Connection conn) throws SQLException {
		Statement s = conn.createStatement();
		s.execute("DROP ALL OBJECTS");
		s.close();
	}


	private void process(Connection conn, boolean continueOnError, String path,
											 Reader reader, Charset charset) throws SQLException, IOException {
		Statement stat = conn.createStatement();
		ScriptReader r = new ScriptReader(reader);
		while (true) {
			String sql = r.readStatement();
			if (sql == null) {
				break;
			}
			String trim = sql.trim();
			if (trim.isEmpty()) {
				continue;
			}
			try {
				if (!trim.startsWith("-->")) {
					logger.log(Level.DEBUG,sql + ";");
				}
				stat.execute(sql);
			} catch (Exception e) {
				if (continueOnError) {
					logger.log(Level.WARN,e);
				} else {
					throw DbException.toSQLException(e);
				}
			}
		}
	}

	//*/
}
