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
import net.sf.hajdbc.distributed.Member;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.sql.AbstractDatabase;
import net.sf.hajdbc.state.sync.SyncMgr;
import net.sf.hajdbc.sync.SynchronizationContext;
import net.sf.hajdbc.util.Resources;
import net.sf.hajdbc.util.StopWatch;
import net.sf.hajdbc.util.ZipUtils;

import java.io.File;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;


/**
 * Dialect for <a href="http://www.h2database.com">H2 Database Engine</a>.
 * @author Paul Ferraro
 */
public class H2Dialect extends StandardDialect
		implements DumpRestoreSupport
{
	static final Logger logger = LoggerFactory.getLogger(H2Dialect.class);
	private static final Set<Integer> failureCodes = new HashSet<Integer>(Arrays.asList(90013, 90030, 90046, 90067, 90100, 90108, 90117, 90121));
	public static final int MIN_DUMP_FILE_SIZE = 1024;

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
	public <Z, D extends Database<Z>> void dump(SynchronizationContext<Z,D> context, D database, Decoder decoder, File file, boolean dataOnly) throws Exception {
		H2RecoveryCoordinator.Session recovery = H2RecoveryCoordinator.current(context == null ? null : context.getDatabaseCluster());
		if (recovery != null) recovery.checkSource(database.getId());
		else {
			H2RecoveryCoordinator<?, ?> coordinator = coordinator(context);
			if (coordinator != null && !coordinator.isEligible(database.getId()))
				throw new SQLException("H2 synchronization source is isolated");
		}
		final String password = database.decodePassword(decoder);
		StopWatch stopWatch = StopWatch.createStarted();
		try(Connection conn = DriverManager.getConnection(database.getLocation(), database.getUser(), password);
        Statement stmt = conn.createStatement())
		{
      stmt.setQueryTimeout(60);
      // 导出失败不能在新会话自动重放 SCRIPT；完成结果未知时拒绝继续恢复。
      conn.setAutoCommit(false);
      H2RestoreDatabaseChecks.requireUserTables(conn, "synchronization source");
      stmt.execute("SCRIPT TO  '" + file.getPath().replace("'", "''") + "'");
      conn.commit();
		}
		stopWatch.stop();
		logger.log(Level.INFO,"h2 dump time={0} path={1}", stopWatch.toString(), file.getPath());
		stopWatch.reset();
		stopWatch.start();
		String zipFilePath = file.getPath() + ".zip";
		ZipUtils.compressFile(file.getPath(), zipFilePath);
		stopWatch.stop();
		logger.log(Level.INFO,"h2 zip dump file time={0} path={1}", stopWatch.toString(), zipFilePath);
	}

	@Override
	public <Z, D extends Database<Z>> void restore(SynchronizationContext<Z,D> context, D database, Decoder decoder, File file, boolean dataOnly) throws Exception {
		H2RecoveryCoordinator.Session recovery = H2RecoveryCoordinator.current(context.getDatabaseCluster());
		if (recovery != null) { recovery.restore(database, file); return; }
		if (coordinator(context) != null) throw new SQLException("H2 restore requires the protected synchronization operation");
		File zipFile = Paths.get(file.getPath() + ".zip").toFile();
		if(database.isLocal()) {
			throw new SQLException("H2 local restore is not supported by this synchronization path");
		}
		StopWatch stopWatch = StopWatch.createStarted();
		SyncMgr syncMgr = context.getDatabaseCluster().getSyncMgr();
		Member target = syncMgr.getMember(database);
		if (target == null) {
			throw new SQLException("H2 restore target member is unavailable");
		}
		if (!syncMgr.upload(target, zipFile)) {
			throw new SQLException("H2 restore upload failed");
		}
		H2RunScriptCommand2 cmd2 = new H2RunScriptCommand2();
		cmd2.setPath(file.getPath());
		// 清库请求只发送一次；未知结果不能重试，也不能降级到旧恢复命令。
		Boolean restored = syncMgr.executeOnce(target, cmd2);
		if (!Boolean.TRUE.equals(restored)) {
			throw new SQLException(restored == null
				? "H2 restore result is unknown; request was not retried"
				: "H2 restore failed on target");
		}
		stopWatch.stop();
		logger.log(Level.INFO,"h2 restore time={0}", stopWatch.toString());
	}

	private static H2RecoveryCoordinator<?, ?> coordinator(SynchronizationContext<?, ?> context) {
		if (context == null || context.getDatabaseCluster() == null || context.getDatabaseCluster().getDistributedManager() == null) return null;
		Object value = context.getDatabaseCluster().getDistributedManager().getExtContext(H2RecoveryCoordinator.class.getName());
		return value instanceof H2RecoveryCoordinator ? (H2RecoveryCoordinator<?, ?>) value : null;
	}

}
