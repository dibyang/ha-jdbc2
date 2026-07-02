package net.sf.hajdbc.sql;

import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.Assert;
import org.junit.Test;

public class DataSourceDatabaseTest
{
	@Test
	public void dbTypeUsesUrlPropertyWhenLocationIsDataSourceClass()
	{
		DataSourceDatabase database = new DataSourceDatabase();
		database.setLocation(JDBCDataSource.class.getName());
		database.setProperty("url", "jdbc:hsqldb:mem:db");

		Assert.assertEquals("hsqldb", database.getDbType());
	}
}
