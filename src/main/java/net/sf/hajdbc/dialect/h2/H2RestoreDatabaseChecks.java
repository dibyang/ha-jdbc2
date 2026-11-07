package net.sf.hajdbc.dialect.h2;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/** H2 全库恢复的最低完整性检查，不把系统表或文件大小当成可同步的业务库。 */
final class H2RestoreDatabaseChecks {
  private H2RestoreDatabaseChecks() { }

  static void requireUserTables(Connection connection, String phase) throws SQLException {
    try (ResultSet tables = connection.getMetaData().getTables(null, null, "%", new String[] { "TABLE", "BASE TABLE" })) {
      while (tables.next()) {
        String schema = tables.getString("TABLE_SCHEM");
        if (!"INFORMATION_SCHEMA".equalsIgnoreCase(schema) && !"PG_CATALOG".equalsIgnoreCase(schema)) return;
      }
    }
    throw new SQLException("H2 " + phase + " contains no user tables");
  }
}
